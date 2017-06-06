/*
 * Copyright (c) 2016 - 2017 Redlink GmbH
 */

package io.redlink.smarti.repositories;

import io.redlink.smarti.model.Conversation;

import org.bson.types.ObjectId;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;

/**
 * Conversation Repository
 *
 * @author Sergio Fernández
 */
public interface ConversationRepository extends CrudRepository<Conversation, ObjectId>, ConversationRepositoryCustom {

    Collection<Conversation> findConversationByUserId(String userId); //TODO: I guess wouldn't work

}
