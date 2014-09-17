#include "gamesys.h"

#include <dlib/log.h>
#include <physics/physics.h>

#include "gamesys_ddf.h"
#include "gamesys.h"
#include "gamesys_private.h"

#include "scripts/script_particlefx.h"
#include "scripts/script_tilemap.h"
#include "scripts/script_physics.h"
#include "scripts/script_sound.h"
#include "scripts/script_sprite.h"
#include "scripts/script_factory.h"
#include "scripts/script_spine_model.h"

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmGameSystem
{

    ScriptLibContext::ScriptLibContext()
    {
        memset(this, 0, sizeof(*this));
    }

    bool InitializeScriptLibs(const ScriptLibContext& context)
    {
        lua_State* L = context.m_LuaState;

        int top = lua_gettop(L);
        (void)top;

        bool result = true;

        ScriptParticleFXRegister(context);
        ScriptTileMapRegister(context);
        ScriptPhysicsRegister(context);
        ScriptFactoryRegister(context);
        ScriptSpriteRegister(context);
        ScriptSoundRegister(context);
        ScriptSpineModelRegister(context);

        assert(top == lua_gettop(L));
        return result;
    }

    void FinalizeScriptLibs(const ScriptLibContext& context)
    {
        ScriptPhysicsFinalize(context);
    }

    dmGameObject::HInstance CheckGoInstance(lua_State* L, uint32_t script_type_mask) {
        dmGameObject::HInstance instance = 0;
        if ((script_type_mask & SCRIPT_TYPE_BIT_LOGIC) != 0) {
            instance = dmGameObject::GetInstanceFromLua(L);
        }
        if (instance == 0 && (script_type_mask & SCRIPT_TYPE_BIT_GUI) != 0) {
            // Not yet implemented, will be supported in the next commit with tests
            // dmGui::HScene scene = dmGui::GetSceneFromLua(L);
            // if (scene != 0) {
            //     instance = (dmGameObject::HInstance)GuiGetUserDataCallback(scene);
            // }
        }
        // No instance for render scripts, ignored
        if (instance == 0) {
            luaL_error(L, "no instance could be found in the current script environment");
        }
        return instance;
    }
}
