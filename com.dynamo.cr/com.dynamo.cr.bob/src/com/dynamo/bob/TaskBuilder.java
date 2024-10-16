// Copyright 2020-2024 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob;

import com.dynamo.bob.TaskResult;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.bundle.BundleHelper;
import com.dynamo.bob.util.TimeProfiler;
import com.dynamo.bob.util.StringUtil;
import com.dynamo.bob.cache.ResourceCache;
import com.dynamo.bob.cache.ResourceCacheKey;
import com.dynamo.bob.logging.Logger;

import java.util.Arrays;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashSet;

import java.io.IOException;
import java.lang.Throwable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;


public class TaskBuilder {

    private static Logger logger = Logger.getLogger(TaskBuilder.class.getName());

    // set of all completed tasks. The set includes both task run
    // in this session and task already completed (output already exists with correct signatures, see below)
    // the set also contains failed tasks
    private Set<Task> completedTasks = new HashSet<>();

    // the set of all output files generated
    // in this or previous session
    private Set<IResource> completedOutputs = new HashSet<>();

    // set of *all* possible output files
    private Set<IResource> allOutputs = new HashSet<>();

    // task results
    private List<TaskResult> results = new ArrayList<>();

    // the tasks to build
    // private List<Task> tasks;
    private Set<Task> tasks;

    private Project project;
    private Map<String, String> options;
    private State state;
    private ResourceCache resourceCache;

    private ExecutorService  executorService;

    public TaskBuilder(List<Task> tasks, Project project) {
        this.tasks = new HashSet<Task>(tasks);
        this.project = project;
        this.options = project.getOptions();
        this.state = project.getState();
        this.resourceCache = project.getResourceCache();

        int nThreads = project.getMaxCpuThreads();
        logger.info("Creating a fixed thread pool executor with %d threads", nThreads);
        this.executorService = Executors.newFixedThreadPool(nThreads);
    }

    private Callable<TaskResult> createCallableTask(final Task task, final IProgress monitor) {
        Callable<TaskResult> callableTask = () -> {
            TaskResult result = buildTask(task, monitor);
            return result;
        };
        return callableTask;
    }
    private Future<TaskResult> submitTask(final Task task, final IProgress monitor) {
        return this.executorService.submit(createCallableTask(task, monitor));
    }

    private boolean compareAllSignatures(byte[] taskSignature, List<IResource> outputResources) {
        boolean allSigsEquals = true;
        for (IResource r : outputResources) {
            byte[] s = state.getSignature(r.getAbsPath());
            if (!Arrays.equals(s, taskSignature)) {
                allSigsEquals = false;
                break;
            }
        }
        return allSigsEquals;
    }

    // deps are the task input files generated by another task not yet completed,
    // i.e. "solve" the dependency graph
    private boolean hasUnresolvedDependencies(Task task) {
        Set<IResource> dependencies = getUnresolvedDependencies(task);
        return !dependencies.isEmpty();
    }
    private Set<IResource> getUnresolvedDependencies(Task task) {
        Set<IResource> dependencies = new HashSet<>(task.getInputs());
        dependencies.retainAll(allOutputs);
        synchronized (completedOutputs) {
            dependencies.removeAll(completedOutputs);
        }
        return dependencies;
    }


    private boolean checkIfResourcesExist(final List<IResource> resources) {
        // do all output files exist?
        boolean allResourcesExists = true;
        for (IResource r : resources) {
            if (!r.exists()) {
                allResourcesExists = false;
                break;
            }
        }
        return allResourcesExists;
    }

    private TaskResult buildTask(Task task, IProgress monitor) throws IOException {
        BundleHelper.throwIfCanceled(monitor);

        logger.info("Build task " + task.getName() + " " + task.getInputsString() + " -> " + task.getOutputsString());

        // if (hasUnresolvedDependencies(task)) {
        //     // postpone task. dependent input not yet generated
        //     // logger.warning("NOT ALL DEPS " + task.getInputsString() + " -> " + task.getOutputsString());
        //     return null;
        // }

        final List<IResource> outputResources = task.getOutputs();

        boolean allOutputsExist = checkIfResourcesExist(outputResources);

        // compare all task signature. current task signature between previous
        // signature from state on disk
        TimeProfiler.start("compare signatures");
        TimeProfiler.addData("color", "#FFC0CB");
        TimeProfiler.addData("main input", String.valueOf(task.input(0)));
        final byte[] taskSignature = task.calculateSignature();
        boolean allSigsEquals = compareAllSignatures(taskSignature, outputResources);
        TimeProfiler.stop();

        boolean isCompleted = completedTasks.contains(task);
        boolean shouldRun = (!allOutputsExist || !allSigsEquals) && !isCompleted;

        if (!shouldRun) {
            if (allOutputsExist && allSigsEquals)
            {
                // Task is successfully completed now or in a previous build.
                // Only if the conditions in the if-statements are true add the task to the completed set and the
                // output files to the completed output set
                synchronized (completedTasks) {
                    completedTasks.add(task);
                }
                synchronized (completedOutputs) {
                    completedOutputs.addAll(outputResources);
                }
            }

            monitor.worked(1);
            return null;
        }

        TimeProfiler.start(task.getName());
        TimeProfiler.addData("output", StringUtil.truncate(task.getOutputsString(), 1000));
        TimeProfiler.addData("type", "buildTask");

        TaskResult taskResult = new TaskResult(task);
        taskResult.setOk(true);
        Builder builder = task.getBuilder();
        Map<IResource, String> outputResourceToCacheKey = new HashMap<IResource, String>();
        try {
            if (task.isCacheable() && resourceCache.isCacheEnabled()) {
                // check if all output resources exist in the resource cache
                boolean allResourcesCached = true;
                for (IResource r : outputResources) {
                    final String key = ResourceCacheKey.calculate(task, options, r);
                    outputResourceToCacheKey.put(r, key);
                    if (!r.isCacheable()) {
                        allResourcesCached = false;
                    }
                    else if (!resourceCache.contains(key)) {
                        allResourcesCached = false;
                    }
                }

                // all resources exist in the cache
                // copy them to the output
                if (allResourcesCached) {
                    TimeProfiler.addData("takenFromCache", true);
                    for (IResource r : outputResources) {
                        r.setContent(resourceCache.get(outputResourceToCacheKey.get(r)));
                    }
                }
                // build task and cache output
                else {
                    builder.build(task);
                    for (IResource r : outputResources) {
                        state.putSignature(r.getAbsPath(), taskSignature);
                        if (r.isCacheable()) {
                            resourceCache.put(outputResourceToCacheKey.get(r), r.getContent());
                        }
                    }
                }
            }
            else {
                builder.build(task);
                for (IResource r : outputResources) {
                    state.putSignature(r.getAbsPath(), taskSignature);
                }
            }
            monitor.worked(1);

            for (IResource r : outputResources) {
                if (!r.exists()) {
                    taskResult.setOk(false);
                    taskResult.setLineNumber(0);
                    taskResult.setMessage(String.format("Output '%s' not found", r.getAbsPath()));
                    break;
                }
            }
            synchronized (completedTasks) {
                completedTasks.add(task);
            }
            synchronized (completedOutputs) {
                completedOutputs.addAll(outputResources);
            }
            TimeProfiler.stop();

        } catch (CompileExceptionError e) {
            logger.severe("COMPILE EXCEPTION " + e);
            TimeProfiler.stop();
            taskResult.setOk(false);
            taskResult.setLineNumber(e.getLineNumber());
            taskResult.setMessage(e.getMessage());
            // to fix the issue it's easier to see the actual callstack
            e.printStackTrace(new java.io.PrintStream(System.out));
        } catch (OutOfMemoryError e) {
            logger.severe("OOM " + e);
            return null;
        } catch (Throwable e) {
            logger.severe("THROWABLE " + e);
            TimeProfiler.stop();
            taskResult.setOk(false);
            taskResult.setLineNumber(0);
            taskResult.setMessage(e.getMessage());
            taskResult.setException(e);
            // to fix the issue it's easier to see the actual callstack
            e.printStackTrace(new java.io.PrintStream(System.out));
        }
        if (!taskResult.isOk()) {
            // Clear sigs for all outputs when a task fails
            for (IResource r : outputResources) {
                state.putSignature(r.getAbsPath(), new byte[0]);
            }
        }
        return taskResult;
    }


    public Set<IResource> getAllOutputs() {
        return allOutputs;
    }


    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<TaskResult> build(IProgress monitor) throws IOException {
        TimeProfiler.start("Build tasks");
        logger.info("Build tasks");
        long tstart = System.currentTimeMillis();

        // Build list of all task outputs
        for (Task task : tasks) {
            allOutputs.addAll(task.getOutputs());
        }

        int previousCompletedCount = 0;
        int previousRemainingCount = 0;
        boolean abort = false;
        List<Callable<TaskResult>> tasksToSubmit = new ArrayList<>();
        Map<String, Integer> taskNameCounter = new HashMap<>();
        while (!tasks.isEmpty() && !abort) {
        // while ((completedTasks.size() < tasks.size()) && !abort) {
            int completedCount = completedTasks.size();
            int remainingCount = tasks.size();

            // if (previousCompletedCount == completedCount && previousRemainingCount == remainingCount) {
            //     logger.info("STALLED!");
            //     for (Task task : tasks) {
            //         logger.info("TASK: " + task.getInputsString() + " -> " + task.getOutputsString());
            //         logger.info(" - Completed " + completedTasks.contains(task));
            //         if (hasUnresolvedDependencies(task)) {
            //             logger.info(" - Unresolved tasks:");
            //             Set<IResource> unresolved = getUnresolvedDependencies(task);
            //             for (IResource r : unresolved) {
            //                 logger.info("   - " + r);
            //             }
            //         }
            //         else {
            //             logger.info(" - All sigs are equal: " + compareAllSignatures(task.calculateSignature(), task.getOutputs()));
            //         }
            //     }
            //     System.exit(0);
            // }
            previousCompletedCount = completedCount;
            previousRemainingCount = remainingCount;
            logger.info("---");
            logger.info("---");
            logger.info("---");
            logger.info("---");
            logger.info("---");
            logger.info("Building tasks - completed: %d remaining: %d", completedTasks.size(), tasks.size());
            tasksToSubmit.clear();
            taskNameCounter.clear();
            Set<String> taskNames = new HashSet<>();
            for (Task task : tasks) {
                String taskName = task.getName();
                if (taskName.equals("VertexProgram") || taskName.equals("FragmentProgram") || taskName.equals("Material")) {
                    taskName = "Shader";
                }
                // limit some task parallelization
                // only one shader builder per iteration (threading issues with stdout)
                // max two atlas builders per iteration (for memory reasons)
                int count = taskNameCounter.getOrDefault(taskName, 0);
                if (taskName.equals("Shader") && (count == 1)) continue;
                if (taskName.equals("Atlas") && (count == 2)) continue;
                if (hasUnresolvedDependencies(task)) continue;
                tasksToSubmit.add(createCallableTask(task, monitor));
                taskNameCounter.put(taskName, count + 1);
            }

            try {
                List<Future<TaskResult>> futures = this.executorService.invokeAll(tasksToSubmit);
                for (Future<TaskResult> future : futures) {
                    TaskResult result = future.get();
                    if (result == null) {
                        continue;
                    }
                    results.add(result);
                    Task task = result.getTask();
                    boolean success = tasks.remove(task);
                    if (!success) {
                        logger.severe("Unable to find task to remove");
                        System.exit(0);
                    }
                    // if an exception was caught we abort the entire build
                    if (result.hasException() || !result.isOk()) {
                        logger.info("Task failed: " + task.getName() + " " + task.getInputsString());
                        abort = true;
                        break;
                    }
                }
            }
            catch (Exception e) {
                logger.severe("Exception");
                e.printStackTrace(new java.io.PrintStream(System.out));
                abort = true;
                break;
            }
        }

        this.executorService.shutdownNow();

        long tend = System.currentTimeMillis();
        logger.info("Build tasks took %f s", (tend-tstart)/1000.0);
        TimeProfiler.stop();
        return results;
    }
}