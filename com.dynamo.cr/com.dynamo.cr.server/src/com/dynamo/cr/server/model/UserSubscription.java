package com.dynamo.cr.server.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.OneToOne;
import javax.persistence.Table;

@Entity
@Table(name = "user_subscriptions")
public class UserSubscription {

    public enum State {
        CANCELED, PENDING, ACTIVE,
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    private User user;

    @ManyToOne(optional = false)
    private Product product;

    @Column(nullable = false, unique = true)
    private Long externalId;

    @Column(nullable = false, unique = true)
    private Long externalCustomerId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private State state = State.PENDING;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Long getExternalId() {
        return externalId;
    }

    public void setExternalId(Long externalId) {
        this.externalId = externalId;
    }

    public Long getExternalCustomerId() {
        return externalCustomerId;
    }

    public void setExternalCustomerId(Long externalCustomerId) {
        this.externalCustomerId = externalCustomerId;
    }

    public State getState() {
        return this.state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "" + this.user + " - " + this.product;
    }

}
