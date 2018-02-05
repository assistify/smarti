/*
 * Copyright 2017 Redlink GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.redlink.smarti.services;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.redlink.smarti.api.StoreService;
import io.redlink.smarti.api.event.StoreServiceEvent;
import io.redlink.smarti.events.ConversationProcessCompleteEvent;
import io.redlink.smarti.model.*;
import io.redlink.smarti.model.config.Configuration;
import io.redlink.smarti.model.result.Result;
import io.redlink.smarti.processing.ProcessingConfiguration;
import io.redlink.smarti.repositories.ConversationRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Conversation-related services
 */
@Service
@EnableConfigurationProperties(ProcessingConfiguration.class)
public class ConversationService {

    private final Logger log = LoggerFactory.getLogger(ConversationService.class);

    @Autowired
    private StoreService storeService;

    @Autowired
    private PrepareService prepareService;

    @Autowired
    private QueryBuilderService queryBuilderService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ConfigurationService confService;

    @Autowired
    private ConversationRepository conversationRepository;

    private final ExecutorService processingExecutor;

    public ConversationService(ProcessingConfiguration processingConfiguration) {
        this.processingExecutor = processingConfiguration.createExecuterService();
    }

    /**
     * Updates the parsed conversation
     * @param client the client
     * @param conversation the conversation (MUST BE owned by the parsed client)
     * @param process if the conversation needs to be processed (done asynchronously)
     * @param onCompleteCallback called after the operation completes (including the optional asynchronous processing)
     * @return the updated conversation as stored after the update and likely before processing has completed. Use the 
     * <code>onCompleteCallback</code> to get the updated conversation including processing results
     */
    public Conversation update(Client client, Conversation conversation, boolean process,
            Consumer<Conversation> onCompleteCallback) {
        Preconditions.checkNotNull(conversation);
        Preconditions.checkNotNull(client);
        if (!Objects.equal(client.getId(), conversation.getOwner())) {
            throw new IllegalStateException("The parsed Client MUST BE the owner of the conversation!");
        }

        final Conversation storedConversation = storeService.store(conversation);
        if (process) {
            process(client, storedConversation, onCompleteCallback);
        } else if (onCompleteCallback != null) {
            onCompleteCallback.accept(storedConversation);
        }
        return conversation;
    }

    /**
     * Appends a message to the end of the conversation
     * @param client the client
     * @param conversation the conversation (MUST BE owned by the parsed client)
     * @param process if the conversation needs to be processed (done asynchronously)
     * @param onCompleteCallback called after the operation completes (including the optional asynchronous processing)
     * @return the updated conversation as stored after the update and likely before processing has completed. Use the 
     * <code>onCompleteCallback</code> to get the updated conversation including processing results
     */
    public Conversation appendMessage(Client client, Conversation conversation, Message message, boolean process,
            Consumer<Conversation> onCompleteCallback) {
        Preconditions.checkNotNull(conversation);
        Preconditions.checkNotNull(message);
        Preconditions.checkNotNull(client);
        if (!Objects.equal(client.getId(), conversation.getOwner())) {
            throw new IllegalStateException("The parsed Client MUST BE the owner of the conversation!");
        }

        final Conversation storedConversation = storeService.appendMessage(conversation, message);

        if (process) {
            process(client, storedConversation, onCompleteCallback);
        } else {
            if (onCompleteCallback != null) {
                onCompleteCallback.accept(storedConversation);
            }
        }

        return storedConversation;
    }

    /**
     * Processes the conversation for the parsed client and saved the processing results if the parsed client is also
     * the owner of the conversation AND the conversation was not updated in the meantime. 
     * @param client
     * @param conv
     * @param onCompleteCallback
     */
    private void process(Client client, final Conversation conv, Consumer<Conversation> onCompleteCallback) {
        final Date lastModified = conv.getLastModified();
        processingExecutor.submit(() -> {
            try {
                Conversation conversation = conv;
                prepareService.prepare(client, conversation);

                templateService.updateTemplates(client, conversation);

                queryBuilderService.buildQueries(client, conversation);

                try {
                    final Conversation result;
                    if (client != null && Objects.equal(conversation.getOwner(), client.getId())) {
                        result = storeService.storeIfUnmodifiedSince(conversation, lastModified);

                        if (log.isDebugEnabled()) {
                            logConversation(result);
                        }

                        eventPublisher.publishEvent(new ConversationProcessCompleteEvent(result));
                    } else {
                        result = conversation;
                    }
                    if (onCompleteCallback != null) {
                        onCompleteCallback.accept(result);
                    }
                } catch (ConcurrentModificationException e) {
                    log.debug("Conversation {} has been modified while analysis was in progress", conversation.getId());
                }
            } catch (Throwable t) {
                log.error("Error during async prepare: {}", t.getMessage(), t);
            }
        });
    }

    private void logConversation(Conversation c) {
        if (!log.isDebugEnabled())
            return;
        log.debug("Conversation[id:{} | channel: {} | modified: {}]", c.getId(), c.getChannelId(),
                c.getLastModified() != null ? DateFormatUtils.ISO_DATETIME_FORMAT.format(c.getLastModified())
                        : "unknown");
        if (c.getUser() != null) {
            log.debug(" > user[id: {}| name: {}] ", c.getUser().getId(), c.getUser().getDisplayName());
        }
        if (c.getMessages() != null) {
            log.debug(" > {} messages:", c.getMessages().size());
            AtomicInteger count = new AtomicInteger(0);
            c.getMessages().forEach(m -> {
                log.debug("    {}. {} : {}", count.incrementAndGet(),
                        m.getUser() == null ? m.getOrigin() : m.getUser().getDisplayName(), m.getContent());
            });
        }
        if (c.getTokens() != null) {
            log.debug(" > {} tokens:", c.getTokens().size());
            AtomicInteger count = new AtomicInteger(0);
            c.getTokens().forEach(t -> {
                log.debug("    {}. {}", count.getAndIncrement(), t);
            });
        }
        if (c.getTemplates() != null) {
            log.debug(" > {} templates:", c.getTemplates().size());
            AtomicInteger count = new AtomicInteger(0);
            c.getTemplates().forEach(t -> {
                log.debug("    {}. {}", count.getAndIncrement(), t);
                if (CollectionUtils.isNotEmpty(t.getQueries())) {
                    log.debug("    > with {} queries", t.getQueries().size());
                    t.getQueries().forEach(q -> log.debug("       - {}", q));
                }
            });
        }
    }

    public Conversation appendMessage(Client client, Conversation conversation, Message message,
            Consumer<Conversation> onCompleteCallback) {
        return appendMessage(client, conversation, message, true, onCompleteCallback);
    }

    public Conversation appendMessage(Client client, Conversation conversation, Message message) {
        return appendMessage(client, conversation, message, true, null);
    }

    public Conversation completeConversation(Conversation conversation) {
        return storeService.completeConversation(conversation.getId());
    }

    public Conversation rateMessage(Conversation conversation, String messageId, int delta) {
        return storeService.adjustMessageVotes(conversation.getId(), messageId, delta);
    }

    public SearchResult<? extends Result> getInlineResults(Client client, Conversation conversation, Template template,
            String creator, MultiValueMap<String, String> params) throws IOException {
        return queryBuilderService.execute(client, creator, template, conversation, params);
    }

    public SearchResult<? extends Result> getInlineResults(Client client, Conversation conversation, Template template,
            String creator) throws IOException {
        return getInlineResults(client, conversation, template, creator, new LinkedMultiValueMap<>());
    }

    public Conversation getConversation(Client client, ObjectId convId) {
        Conversation conversation = storeService.get(convId);
        if (conversation == null) {
            return null;
        }
        return updateQueries(client, conversation);
    }

    private Conversation updateQueries(Client client, Conversation conversation) {
        if (conversation == null)
            return null;

        Configuration config;
        if (client == null) {
            config = confService.getClientConfiguration(conversation.getOwner());
        } else {
            config = confService.getClientConfiguration(client);
        }
        if (config == null) {
            log.debug("Client {} does not have a configuration. Will use default configuration", client);
            config = confService.getDefaultConfiguration();
        }
        Date confModDate = config.getModified();
        if (confModDate == null || conversation.getLastModified().before(confModDate)) {
            log.debug("update queries for {} because after configuration change", conversation);
            queryBuilderService.buildQueries(config, conversation);
            if (Objects.equal(conversation.getOwner(), config.getClient())) {//only store updated queries if we used the owners conviguration
                conversation = storeService.storeIfUnmodifiedSince(conversation, conversation.getLastModified());
            } //TODO: when we add a query cache we could also cache queries for other clients as the owner of the conversation
        }
        return conversation;
    }

    public Conversation getCurrentConversationByChannelId(Client client, String channelId) {
        return getCurrentConversationByChannelId(client, channelId, Conversation::new);
    }

    public Conversation getCurrentConversationByChannelId(Client client, String channelId,
            Supplier<Conversation> supplier) {
        Preconditions.checkNotNull(client);
        Preconditions.checkArgument(StringUtils.isNoneBlank(channelId));
        final ObjectId conversationId = storeService.mapChannelToCurrentConversationId(channelId);
        if (conversationId != null) {
            Conversation conversation = storeService.get(conversationId);
            if (Objects.equal(conversation.getOwner(), client.getId())) {
                return getConversation(client, conversationId);
            } else {
                //this should never happen unless we have two clients with the same channelId
                throw new IllegalStateException("Conversation for channel '" + channelId + "' has a different owner "
                        + "as the current client (owner: " + conversation.getOwner() + ", client: " + client + ")!");
            }
        } else {
            final Conversation c = supplier.get();
            if (c != null) {
                c.setId(null);
                c.setOwner(client.getId());
                c.setChannelId(channelId);
                return storeService.store(c);
            } else {
                return null;
            }
        }
    }

    public Page<Conversation> listConversations(ObjectId clientId, int page, int pageSize) {
        final PageRequest paging = new PageRequest(page, pageSize);
        if (java.util.Objects.nonNull(clientId)) {
            return conversationRepository.findByOwner(clientId, paging);
        } else {
            return conversationRepository.findAll(paging);
        }
    }

    public Conversation getConversation(ObjectId conversationId) {
        return storeService.get(conversationId);
    }

    public List<Conversation> getConversations(ObjectId owner) {
        return conversationRepository.findByOwner(owner);
    }

    public void importConversations(ObjectId owner, List<Conversation> conversations) {
        importConversations(owner, conversations, false);
    }

    public void importConversations(ObjectId owner, List<Conversation> conversations, boolean replace) {
        // TODO(westei): implement this method
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public boolean exists(ObjectId conversationId) {
        return conversationRepository.exists(conversationId);
    }

    public Conversation updateStatus(ObjectId conversationId, ConversationMeta.Status newStatus) {
        return publishSaveEvent(
                updateQueries(null, conversationRepository.updateConversationStatus(conversationId, newStatus)));
    }

    public boolean deleteMessage(ObjectId conversationId, String messageId) {
        final boolean success = conversationRepository.deleteMessage(conversationId, messageId);
        if (success) {
            final Conversation one = conversationRepository.findOne(conversationId);
            publishSaveEvent(updateQueries(null, one));
        }
        return success;
    }

    public Conversation updateMessage(ObjectId conversationId, Message updatedMessage) {
        return publishSaveEvent(
                updateQueries(null, conversationRepository.updateMessage(conversationId, updatedMessage)));
    }

    private Conversation publishSaveEvent(Conversation conversation) {
        Preconditions.checkNotNull(conversation, "Can't publish <null> conversation");
        eventPublisher
                .publishEvent(StoreServiceEvent.save(conversation.getId(), conversation.getMeta().getStatus(), this));
        return conversation;
    }

    public Conversation deleteConversation(ObjectId conversationId) {
        final Conversation one = conversationRepository.findOne(conversationId);
        if (one != null) {
            conversationRepository.delete(conversationId);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(StoreServiceEvent.delete(conversationId, this));
            }
            if (analysisRepository != null) {
                try {
                    analysisRepository.deleteByConversation(one.getId());
                } catch (RuntimeException e) {
                    log.debug("Unable to delete storead analysis for deleted conversation {}", one, e);
                }
            }
        }
        return one;
    }

    /**
     * Updates a field of the conversation<p>
     * If the parsed field does not contain '<code>.</code>' the '<code>meta.</code>' prefix
     * is assumed. <p>
     * supported fields include (format: '{mongo-field} ({json-field-1}, {json-field-2} ...)'<ul>
     * <li>'context.contextType' (context.contextType)
     * <li>'context.domain' (context.domain)
     * <li>'context.environment.*' (context.environment.*, environment.*)
     * <li>'meta.status' (meta.status, status)
     * <li>'meta.properties.*' (meta.*, *)
     * </ul>
     * @param conversationId the id of the conversation (MUST NOT be NULL)
     * @param field the name of the field. See description for supported values (MUST NOT be NULL)
     * @param data the value for the vield (MUST NOT be NULL)
     * @return
     */
    public Conversation updateConversationField(ObjectId conversationId, final String field, Object data) {
        if (conversationId == null) {
            throw new NullPointerException();
        }
        if (data == null) {
            throw new BadArgumentException("data", null, "the parsed field data MUST NOT be NULL!");
        }
        final String mongoField = toMongoField(field);

        //handle special cases
        if ("meta.status".equals(mongoField)) {
            //meta status is an enumeration so only some values are allowed
            try {
                return updateStatus(conversationId, Status.valueOf(data.toString()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BadArgumentException(field, data, "supported values are NULL or " + Status.values());
            }
        } else { //deal the default case
            log.debug("set conversation field {}: {} (parsed field: {})", mongoField, data, field);
            //we need to map fields to conversation paths
            return publishSaveEvent(conversationRepository.updateConversationField(conversationId, mongoField, data));
        }
    }

    public Conversation deleteConversationField(ObjectId conversationId, String field) {
        if (conversationId == null) {
            throw new NullPointerException();
        }

        final String mongoField = toMongoField(field);

        //handle special cases
        if ("meta.status".equals(mongoField)) {
            //one can not delete the status!
            throw new BadArgumentException(field, null, "this field can not be deleted!");
        } else { //deal the default case
            log.debug("delete conversation field {}: {} (parsed field: {})", mongoField, field);
            //we need to map fields to conversation paths
            return publishSaveEvent(conversationRepository.deleteConversationField(conversationId, mongoField));
        }
    }

    /**
     * Internally used to map a parsed field name to the field path as used in Mongo. <p>
     * If the field does not contain '<code>.</code>' the '<code>meta.</code>' prefix
     * is assumed. <p>
     * supported fields include (format: '{mongo-field} ({json-field-1}, {json-field-2} ...)'<ul>
     * <li>'context.contextType' (context.contextType)
     * <li>'context.domain' (context.domain)
     * <li>'context.environment.*' (context.environment.*, environment.*)
     * <li>'meta.status' (meta.status, status)
     * <li>'meta.properties.*' (meta.*, *)
     * </ul>
     * @param field the parsed field
     * @return
     */
    private String toMongoField(final String field) {
        if (StringUtils.isBlank(field)) {
            throw new BadArgumentException("field", "The parsed field MUST NOT be blank");
        }
        final String jsonField;
        final String mongoField;
        if (field.indexOf('.') < 0) { //no prefix parsed ... use 'meta' as default
            jsonField = "meta." + field;
        } else {
            jsonField = field;
        }
        int sepIdx = jsonField.indexOf('.') + 1; //we do want to cut the '.'
        if (jsonField.startsWith("context.")) {
            String fieldName = jsonField.substring(sepIdx);
            if (!("contextType".equals(fieldName) || "domain".equals(fieldName)
                    || fieldName.startsWith("environment."))) {
                throw new BadArgumentException("field", "Unsupported context field '" + field
                        + "' (unknown context field: '" + fieldName + "' known: contextType, domain, environment.*)");
            }
            mongoField = "context." + fieldName;
        } else if (jsonField.startsWith("meta.")) {
            String fieldName = jsonField.substring(sepIdx);
            if ("status".equals(fieldName)) {
                mongoField = "meta." + fieldName;
            } else {
                mongoField = "meta.properties." + fieldName;
            }
        } else if (jsonField.startsWith("environment.")) {
            mongoField = "context." + jsonField;
        } else {
            throw new BadArgumentException("field", "Unsupported field name '" + field + "' (unknown prefix: '"
                    + jsonField.substring(sepIdx) + "' known: context, meta)");
        }
        return mongoField;
    }

    public Message getMessage(ObjectId conversationId, String messageId) {
        return conversationRepository.findMessage(conversationId, messageId);
    }

    public boolean exists(ObjectId conversationId, String messageId) {
        return conversationRepository.exists(conversationId, messageId);
    }

    /**
     * Updates a field of a message within the conversation
     * @param conversationId
     * @param messageId
     * @param field
     * @param data
     * @return The conversation on an update or <code>null</code> of no update was performed
     */
    public Conversation updateMessageField(ObjectId conversationId, String messageId, String field, Object data) {
        if (conversationId == null) {
            throw new NullPointerException();
        }
        if (messageId == null) {
            throw new NullPointerException();
        }
        if (data == null) {
            throw new BadArgumentException("data", null, "the parsed field data MUST NOT be NULL!");
        }
        //TODO validate message field
        if ("orign".equals(field)) {
            try {
                Origin.valueOf(data.toString());
            } catch (IllegalArgumentException e) {
                throw new BadArgumentException(field, data,
                        "unsupported value (supported: " + Arrays.toString(Origin.values()) + ")");
            }
        } else if ("private".equals(field)) {
            if (!(data instanceof Boolean)) {
                data = Boolean.parseBoolean(data.toString());
            }
            field = "_private";
        } else if ("votes".equals(field)) {
            if (!(data instanceof Integer)) {
                try {
                    data = Integer.parseInt(data.toString());
                } catch (NumberFormatException e) {
                    throw new BadArgumentException(field, data, "not a valid integer");
                }
            }
        } else if ("content".equals(field)) {
            if (!(data instanceof String)) {
                throw new BadArgumentException(field, data, "MUST be a String");
            }
        } else if ("time".equals(field)) {
            if (data instanceof Long) {
                data = new Date((Long) data);
            } else if (data instanceof String) {
                try {
                    data = Date.from(Instant.parse(data.toString()));
                } catch (DateTimeParseException e) {
                    try {
                        data = DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.parse(data.toString());
                    } catch (java.text.ParseException e1) {
                        throw new BadArgumentException(field, data,
                                "not a valid date (supported: long time, ISO date/time format)");
                    }
                }
            } else if (!(data instanceof Date)) {
                throw new BadArgumentException(field, data,
                        "not a valid date (supported: long time, ISO date/time format)");
            }
        } else if (field.startsWith("metadata.")) {
            throw new BadArgumentException(field, data,
                    "Unknown field (supported: time, origin, content, private, votes, metadata.*)");
        }
        //time, origin, content, private, votes, metadata.*
        final Conversation con = conversationRepository.updateMessageField(conversationId, messageId, field, data);
        if (con != null) {
            publishSaveEvent(con);
        }
        return con;
    }

    protected final Conversation store(Conversation conversation) {
        conversation.setLastModified(new Date());
        if (conversation.getId() != null) { //if we update an existing we need to validate the clientId value
            Conversation persisted = getConversation(conversation.getId());
            if (persisted == null) {
                throw new NotFoundException(Conversation.class, conversation.getId());
            } else {
                if (conversation.getOwner() == null) {
                    conversation.setOwner(persisted.getOwner());
                } else if (!Objects.equals(conversation.getOwner(), persisted.getOwner())) {
                    throw new ConflictException(Conversation.class, "clientId",
                            "The clientId MUST NOT be changed for an existing conversation!");
                }
            }
        } else { //create a new conversation
            //TODO: Maybe we should check if the Owner exists
            if (conversation.getOwner() == null) {
                throw new ConflictException(Conversation.class, "owner",
                        "The owner MUST NOT be NULL nor empty for a new conversation!");
            }

        }
        return publishSaveEvent(conversationRepository.save(conversation));
    }

    public final Collection<ObjectId> listConversationIDsByUser(String userId) {
        return conversationRepository.findConversationIDsByUser(userId);
    }

    public Conversation adjustMessageVotes(ObjectId conversationId, String messageId, int delta) {
        return conversationRepository.adjustMessageVotes(conversationId, messageId, delta);
    }

    public Iterable<ObjectId> listConversationIDs() {
        return conversationRepository.findConversationIDs();
    }

    public Conversation findLegacyConversation(Client owner, String contextType, String channelId) {
        return conversationRepository.findLegacyConversation(owner.getId(), contextType, channelId);
    }
}
