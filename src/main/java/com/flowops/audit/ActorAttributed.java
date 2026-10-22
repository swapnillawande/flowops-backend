package com.flowops.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

/** Durable creator identity shared by append-only audit rows, independent of mutable display names. */
@MappedSuperclass
@EntityListeners(ActorAttributionListener.class)
public abstract class ActorAttributed {
    @Column(name = "actor_subject", columnDefinition = "text", updatable = false)
    private String actorSubject;
    @Column(name = "actor_issuer", columnDefinition = "text", updatable = false)
    private String actorIssuer;

    @Column(name = "actor_name", columnDefinition = "text", updatable = false)
    private String actorName;

    // Package-private: DTOs and callers cannot submit an audit identity or replace its original creator.
    void captureActor(String issuer, String subject, String name) {
        this.actorIssuer = issuer;
        this.actorSubject = subject;
        this.actorName = name;
    }

    public String getActorSubject() { return actorSubject; }
    public String getActorName() { return actorName; }
    public String getActorIssuer() { return actorIssuer; }
}
