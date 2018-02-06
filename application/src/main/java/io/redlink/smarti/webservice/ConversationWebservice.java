
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

import com.google.common.collect.ImmutableMap;
import io.redlink.smarti.exception.DataException;
import io.redlink.smarti.exception.NotFoundException;
import io.redlink.smarti.model.*;
import io.redlink.smarti.model.config.Configuration;
import io.redlink.smarti.model.result.Result;
import io.redlink.smarti.services.AuthenticationService;
import io.redlink.smarti.services.ConversationService;
import io.redlink.smarti.utils.ResponseEntities;
import io.redlink.smarti.webservice.pojo.AuthContext;
import io.redlink.smarti.webservice.pojo.QueryUpdate;
import io.redlink.smarti.webservice.pojo.TemplateResponse;
import io.swagger.annotations.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 *
 */
@SuppressWarnings({ "unused", "WeakerAccess", "DefaultAnnotationParam" })
@CrossOrigin
@RestController
@RequestMapping(value = "/conversation", produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
@Api
public class ConversationWebservice {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @SuppressWarnings("unused")
    private enum Vote {
        up(1), down(-1);

        private final int delta;

        private final CallbackService callbackExecutor;
        private final ConversationService conversationService;
        private final AnalysisService analysisService;
        private final ConversationSearchService conversationSearchService;
        private final MessageSearchService messageSearchService;
        private final AuthenticationService authenticationService;

        public int getDelta() {
            return delta;
        }
    }

    //@Autowired
    //private StoreService storeService;

    @Autowired
    public ConversationWebservice(AuthenticationService authenticationService, ConversationService conversationService,
            AnalysisService analysisService, CallbackService callbackExecutor,
            Optional<ConversationSearchService> conversationSearchService,
            Optional<MessageSearchService> messageSearchService) {
        this.callbackExecutor = callbackExecutor;
        this.conversationService = conversationService;
        this.analysisService = analysisService;
        this.conversationSearchService = conversationSearchService.orElse(null);
        this.messageSearchService = messageSearchService.orElse(null);
        this.authenticationService = authenticationService;
    }

    @ApiOperation(value = "list conversations", code = 200, response = PagedConversationList.class,
            notes = "Lists conversations. Supports pagination (default "+DEFAULT_PAGE_SIZE + "conversation per page)")
    @RequestMapping(method = RequestMethod.GET)
    public Page<ConversationData> listConversations(
            AuthContext authContext,
            @ApiParam(name=PARAM_CLIENT_ID,allowMultiple=true,required=false, value=DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) List<ObjectId> owners,
            @ApiParam(name=PARAM_PAGE, required=false, defaultValue="0", value=DESCRIPTION_PARAM_PAGE) @RequestParam(value = PARAM_PAGE, required = false, defaultValue = "0") int page,
            @ApiParam(name=PARAM_PAGE_SIZE, required=false, defaultValue=DEFAULT_PAGE_SIZE, value=DESCRIPTION_PARAM_PAGE_SIZE) @RequestParam(value = PARAM_PAGE_SIZE, required = false, defaultValue = DEFAULT_PAGE_SIZE) int pageSize,
            @ApiParam(name=PARAM_PROJECTION, required=false, value=DESCRIPTION_PARAM_PROJECTION) @RequestParam(value = PARAM_PROJECTION, required = false) Projection projection
    ) {
        final Set<ObjectId> clientIds = getClientIds(authContext, owners);
        if(CollectionUtils.isNotEmpty(clientIds)){
            return conversationService.listConversations(clientIds, page, pageSize)
                    .map(ConversationData::fromModel);
        } else {
            return new PageImpl<>(Collections.emptyList(), new PageRequest(page, pageSize), 0);
        }

    @Autowired
    private ConfigurationService configService;

    @Autowired
    private AuthenticationService authenticationService;

    @ApiOperation(value = "create a conversation")
    @ApiResponses({ @ApiResponse(code = 201, message = "Created", response = Conversation.class) })
    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<?> createConversation(AuthContext authContext,
            @RequestBody(required = false) Conversation conversation) {
        conversation = Optional.ofNullable(conversation).orElseGet(Conversation::new);
        // Create a new Conversation -> id must be null
        conversation.setId(null);

        final Set<Client> clients = authenticationService.assertClients(authContext);
        final Client client;
        if (clients.isEmpty()) {
            return ResponseEntity.badRequest().build();
        } else if (clients.size() == 1) {
            client = clients.iterator().next();
        } else {
            final ObjectId ownerId = conversation.getOwner();
            client = clients.stream().filter(c -> c.getId().equals(ownerId)).findFirst().orElse(null);
        }
        if (client == null) {
            throw new IllegalStateException("Owner for conversation " + conversation.getId() + " not found!");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.update(client, conversation, true, null));
    }

    @ApiOperation(value = "retrieve a conversation", response = Conversation.class)
    @RequestMapping(value = "{id}", method = RequestMethod.GET)
    public ResponseEntity<?> getConversation(AuthContext authContext, @PathVariable("id") ObjectId id) {

        final Set<ObjectId> clientIds = getClientIds(authContext, owners);

        if (conversationSearchService != null) {
            try {
                return ResponseEntity.ok(conversationSearchService.search(clientIds, queryParams));
            } catch (IOException e) {
                return ResponseEntities.internalServerError(e);
            }
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /**
     * Allow conversation-independent search.
     */
    @ApiOperation(value = "search for messages", response = SearchResult.class, notes = "Search for messages. You can pass in arbitrary solr query parameter (`q` for the query, "
            + "`fq` for filters, facets, grouping, highligts, ...). Results represent messages. \n\n "
            + "Fields include: \n" + "* `id`: the id of the conversation \n"
            + "* `message_id`: the id of the message \n"
            + "* `meta_*`: meta fields set for the conversation ( e.g. `meta_channel_id` for the channel id)\n"
            + "* `user_id`: the id of the user that was sending the message\n"
            + "* `time`: the time when the message was sent\n" + "* `message`: the content of the message")
    @RequestMapping(value = "search-message", method = RequestMethod.GET)
    public ResponseEntity<?> searchMessage(AuthContext authContext,
            @ApiParam(name = PARAM_CLIENT_ID, allowMultiple = true, required = false, value = DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) List<ObjectId> owners,
            @ApiParam(hidden = true) @RequestParam MultiValueMap<String, String> queryParams) {
        final Set<ObjectId> clientIds = getClientIds(authContext, owners);
        if (log.isTraceEnabled()) {
            log.debug("{}[{}]: message-search for '{}'", clientIds, authContext, queryParams.get("q"));
        } else {
            log.debug("{}: message-search for '{}'", clientIds, queryParams.get("q"));
        }

        if (messageSearchService != null) {
            try {
                return ResponseEntity.ok(messageSearchService.search(clientIds, queryParams));
            } catch (IOException e) {
                return ResponseEntities.internalServerError(e);
            }
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ApiOperation(value = "Retrieve a conversation", code = 200, response = ConversationData.class, notes = "Retrieves a conversation based on the `conversationId`. If `"
            + PARAM_ANALYSIS + "` is enabled the " + "analysis results will be included in the response")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "if no conversation with the parsed id is present or not accessible by the authenticated user"),
            @ApiResponse(code = 400, message = "in case `" + PARAM_ANALYSIS
                    + "=true` the request requires a single client to be"
                    + "specified to calculate the analysis for. If the authenticated user is assigend to multiple clients and the"
                    + "parameter `" + PARAM_CLIENT_ID + "` is not specified a `400 Bad Request` is triggered") })
    @RequestMapping(value = "{conversationId}", method = RequestMethod.GET)
    public ResponseEntity<ConversationData> getConversation(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId,
            @ApiParam(name = PARAM_CLIENT_ID, required = false, value = DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId,
            @ApiParam(name = PARAM_ANALYSIS, required = false, defaultValue = "false", value = DESCRIPTION_PARAM_ANALYSIS_NO_CALLBACK) @RequestParam(value = PARAM_ANALYSIS, defaultValue = "false") boolean inclAnalysis,
            @ApiParam(name = PARAM_PROJECTION, required = false, value = DESCRIPTION_PARAM_PROJECTION) @RequestParam(value = PARAM_PROJECTION, required = false) Projection projection) {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        if (conversation == null) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(conversation);
        }
    }

    @ApiOperation(value = "update a conversation", response = Conversation.class)
    @RequestMapping(value = "{id}", method = RequestMethod.PUT, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateConversation(AuthContext authContext, @PathVariable("id") ObjectId id,
            @RequestBody Conversation conversation) {
        final Conversation storedC = authenticationService.assertConversation(authContext, id);

        // make sure the id is the right one
        conversation.setId(storedC.getId());
        final Client client = authenticationService.assertClient(authContext, conversation.getOwner());

        //TODO: check that the
        // * the user is from the client the stored conversation as as owner
        return ResponseEntity.ok(conversationService.update(client, conversation, true, null));
    }

    @ApiOperation(value = "update/modify a specific field", response = ConversationData.class, notes = "Sets a single property in the Conversation to the value parsed in the payload."
            + EDITABLE_CONVERSATION_FIELDS + API_ASYNC_NOTE, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    @ApiResponses({ @ApiResponse(code = 200, message = "field updated (sync)", response = ConversationData.class) })
    @RequestMapping(value = "{conversationId}/{field:.*}", method = RequestMethod.PUT, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationData> modifyConversationField(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId,
            @ApiParam(value = "the field to update", required = true, allowableValues = CONVERSATION_FIELD_VALUES) @PathVariable("field") String field,
            @ApiParam(name = PARAM_CLIENT_ID, required = false, value = DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId,
            @ApiParam(value = "the new value for the field", required = true) @RequestBody Object data,
            @ApiParam(name = PARAM_ANALYSIS, required = false, defaultValue = "false", value = DESCRIPTION_PARAM_ANALYSIS) @RequestParam(value = PARAM_ANALYSIS, defaultValue = "false") boolean inclAnalysis,
            @ApiParam(name = PARAM_CALLBACK, required = false, value = DESCRIPTION_PARAM_CALLBACK) @RequestParam(value = PARAM_CALLBACK, required = false) URI callback,
            @ApiParam(name = PARAM_PROJECTION, required = false, value = DESCRIPTION_PARAM_PROJECTION) @RequestParam(value = PARAM_PROJECTION, required = false) Projection projection) {
        // Check access to the conversation
        Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            if (inclAnalysis) {
                throw e;
            } else {
                client = null;
            }
        }

        if (conversationService.exists(conversationId)) {
            Conversation updated = conversationService.updateConversationField(conversationId, field, data);
            CompletableFuture<Analysis> analysis = analysisService.analyze(client, updated);
            if (callback != null) {
                analysis.whenComplete((a, e) -> {
                    if (a != null) {
                        log.debug("callback {} with {}", callback, a);
                        callbackExecutor.execute(callback, CallbackPayload.success(a));
                    } else {
                        log.warn("Analysis of {} failed sending error callback to {} ({} - {})", updated.getId(),
                                callback, e, e.getMessage());
                        log.debug("STACKTRACE: ", e);
                        callbackExecutor.execute(callback, CallbackPayload.error(e));
                    }
                });
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                                    buildAnalysisURI(uriBuilder, conversationId)))
                    .body(toConversationData(client, updated, analysis, inclAnalysis));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "deletes the specific field", response = ConversationData.class, httpMethod = "DELETE", notes = "Deleting a single property in the Conversation."
            + EDITABLE_CONVERSATION_FIELDS + API_ASYNC_NOTE)
    @ApiResponses({ @ApiResponse(code = 200, message = "field deleted (sync)", response = Conversation.class) })
    @RequestMapping(value = "{conversationId}/{field:.*}", method = RequestMethod.DELETE)
    public ResponseEntity<ConversationData> deleteConversationField(AuthContext authContext,
            @PathVariable("id") ObjectId id, @RequestBody Message message) {
        final Conversation conversation = authenticationService.assertConversation(authContext, id);

        final Client client = authenticationService.assertClient(authContext, conversation.getOwner());

        if (conversationService.exists(conversationId)) {
            Conversation updated = conversationService.deleteConversationField(conversationId, field);
            CompletableFuture<Analysis> analysis = analysisService.analyze(client, updated);
            if (callback != null) {
                analysis.whenComplete((a, e) -> {
                    if (a != null) {
                        log.debug("callback {} with {}", callback, a);
                        callbackExecutor.execute(callback, CallbackPayload.success(a));
                    } else {
                        log.warn("Analysis of {} failed sending error callback to {} ({} - {})", updated.getId(),
                                callback, e, e.getMessage());
                        log.debug("STACKTRACE: ", e);
                        callbackExecutor.execute(callback, CallbackPayload.error(e));
                    }
                });
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                                    buildAnalysisURI(uriBuilder, conversationId)))
                    .body(toConversationData(client, updated, analysis, inclAnalysis));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "list the messages in a conversation", response = Message.class, responseContainer = "List", notes = "retrieves all messages in the accessed conversation")
    @RequestMapping(value = "{conversationId}/message", method = RequestMethod.GET)
    public ResponseEntity<List<Message>> listMessages(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId,
            @ApiParam(name = PARAM_PROJECTION, required = false, value = DESCRIPTION_PARAM_PROJECTION) @RequestParam(value = PARAM_PROJECTION, required = false) Projection projection) {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                            buildAnalysisURI(uriBuilder, conversationId)))
                    .body(conversation.getMessages());
        }
    }

    @ApiOperation(value = "up-/down-vote a message within a conversation", response = Conversation.class)
    @RequestMapping(value = "{id}/message/{messageId}/{vote}", method = RequestMethod.PUT)
    public ResponseEntity<?> rateMessage(AuthContext authContext, @PathVariable("id") ObjectId id,
            @PathVariable("messageId") String messageId, @PathVariable("vote") Vote vote) {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            client = null;
        }

        //NOTE: ID generation for sub-documents is not supported by Mongo
        if (StringUtils.isBlank(message.getId())) {
            message.setId(UUID.randomUUID().toString());
        }
        //TODO: should we set the time or is it ok to have messages without time?
        if (message.getTime() == null) {
            message.setTime(new Date());
        }
        Conversation c = conversationService.appendMessage(conversation, message);
        final Message created = c.getMessages().stream().filter(m -> Objects.equals(message.getId(), m.getId()))
                .findAny().orElseThrow(() -> new IllegalStateException(
                        "Created Message[id: " + message.getId() + "] not present in " + c));

        CompletableFuture<Analysis> analysis = analysisService.analyze(client, c);
        if (callback != null) {
            analysis.whenComplete((a, e) -> {
                if (a != null) {
                    log.debug("callback {} with {}", callback, a);
                    callbackExecutor.execute(callback, CallbackPayload.success(a));
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", c.getId(), callback, e,
                            e.getMessage());
                    log.debug("STACKTRACE: ", e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
        }
        URI messageLocation = buildMessageURI(uriBuilder, conversationId, created.getId());
        return ResponseEntity.created(messageLocation)
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"self\"", messageLocation))
                .header(HttpHeaders.LINK,
                        String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                buildConversationURI(uriBuilder, conversationId)))
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                        buildAnalysisURI(uriBuilder, conversationId)))
                .body(created);
    }

    @ApiOperation(value = "retrieve the analysis result of the conversation", response = Token.class, responseContainer = "List")
    @RequestMapping(value = "{id}/analysis", method = RequestMethod.GET)
    public ResponseEntity<?> prepare(AuthContext authContext, @PathVariable("id") ObjectId id) {
        final Conversation conversation = authenticationService.assertConversation(authContext, id);

        return ResponseEntity.ok(conversation.getTokens());
    }

    @ApiOperation(value = "retrieve the intents of the conversation", response = TemplateResponse.class)
    @RequestMapping(value = "{id}/template", method = RequestMethod.GET)
    public ResponseEntity<?> query(AuthContext authContext, @PathVariable("id") ObjectId id) {
        // Check authentication
        Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            client = null; //pre-calculate the analysis for the owner
        }

        //make sure the message-id is the addressed one
        message.setId(messageId);
        final Conversation c = conversationService.updateMessage(conversationId, message);
        final Message updated = c.getMessages().stream().filter(m -> Objects.equals(messageId, m.getId())).findAny()
                .orElseThrow(
                        () -> new IllegalStateException("Updated Message[id: " + messageId + "] not present in " + c));
        CompletableFuture<Analysis> analysis = analysisService.analyze(client, c);
        if (callback != null) {
            analysis.whenComplete((a, e) -> {
                if (a != null) {
                    log.debug("callback {} with {}", callback, a);
                    callbackExecutor.execute(callback, CallbackPayload.success(a));
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", c.getId(), callback, e,
                            e.getMessage());
                    log.debug("STACKTRACE: ", e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK,
                        String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                buildMessageURI(uriBuilder, conversationId, updated.getId())))
                .header(HttpHeaders.LINK,
                        String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                buildConversationURI(uriBuilder, conversationId)))
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                        buildAnalysisURI(uriBuilder, conversationId)))
                .body(updated);
    }

    @ApiOperation(value = "retrieve the results for a template from a specific creator", response = InlineSearchResult.class)
    @RequestMapping(value = "{id}/template/{template}/{creator}", method = RequestMethod.GET)
    public ResponseEntity<?> getResults(
            AuthContext authContext,
            @PathVariable("id") ObjectId id,
            @PathVariable("template") int templateIdx,
            @PathVariable("creator") String creator,
            @ApiParam(hidden = true) @RequestParam(required = false) MultiValueMap<String, String> params
    ) {
        final Conversation conversation = authenticationService.assertConversation(authContext, id);
        final Client client = authenticationService.assertClient(authContext, conversation.getOwner());

        try {
            final Template template = conversation.getTemplates().get(templateIdx);

        if(conversationService.deleteMessage(conversationId, messageId)){
            Conversation c = conversationService.getConversation(conversationId);
            CompletableFuture<Analysis> analysis = analysisService.analyze(client, c);
            if(callback != null){
                analysis.whenComplete((a , e) -> {
                    if(a != null){
                        log.debug("callback {} with {}", callback, a);
                        callbackExecutor.execute(callback, CallbackPayload.success(a));
                    } else {
                        log.warn("Analysis of {} failed sending error callback to {} ({} - {})", c.getId(), callback, e, e.getMessage());
                        log.debug("STACKTRACE: ",e);
                        callbackExecutor.execute(callback, CallbackPayload.error(e));
                    }
                });
            }
            return ResponseEntity.noContent()
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"up\"", buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"", buildAnalysisURI(uriBuilder, conversationId)))
                    .build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "update/modify a specific filed of the message", response = Message.class,
            consumes=MimeTypeUtils.APPLICATION_JSON_VALUE,
            notes = "Sets the property of the Message to the parsed value" 
                    + EDITABLE_MESSAGE_FIELDS + API_ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(code = 200, message = "field updated Message", response = Message.class),
            @ApiResponse(code = 404, message = "if the conversation or message was not found"),
            @ApiResponse(code = 400, message = "if the value is not valid for the field")
    })
    @RequestMapping(value = "{conversationId}/message/{msgId}/{field}", method = RequestMethod.PUT, consumes=MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<Message> modifyMessageField(
            AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId,
            @PathVariable("msgId") String messageId,
            @ApiParam(value = "the field to update", required = true, allowableValues = MESSAGE_FIELD_VALUES) @PathVariable("field") String field,
            @ApiParam(name=PARAM_CLIENT_ID, required=false, value=DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId,
            @ApiParam(value = "the new value", required = true) @RequestBody Object data,
            @ApiParam(name=PARAM_CALLBACK, required=false, value=DESCRIPTION_PARAM_CALLBACK) @RequestParam(value = PARAM_CALLBACK, required = false) URI callback
    ) {
        // Check authentication
        Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            client = null;
        }

        Conversation c = conversationService.updateMessageField(conversationId, messageId, field, data);
        final Message updated = c.getMessages().stream()
                .filter(m -> Objects.equals(messageId, m.getId()))
                .findAny().orElseThrow(() -> new IllegalStateException(
                        "Updated Message[id: "+messageId+"] not present in " + c));
        CompletableFuture<Analysis> analysis = analysisService.analyze(client, c);
        if(callback != null){
            analysis.whenComplete((a , e) -> {
                if(a != null){
                    log.debug("callback {} with {}", callback, a);
                    callbackExecutor.execute(callback, CallbackPayload.success(a));
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", c.getId(), callback, e, e.getMessage());
                    log.debug("STACKTRACE: ",e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"self\"", buildMessageURI(uriBuilder, conversationId, updated.getId())))
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"up\"", buildConversationURI(uriBuilder, conversationId)))
                .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"", buildAnalysisURI(uriBuilder, conversationId)))
                .body(updated);
    }

    @ApiOperation(value = "analysis a conversation", notes = "retrieve the analysis results for a conversation referenced by the `conversationId`. The"
            + "analysis includes extraceted tokens, templates and query suggestions based on those templates.\n\n"
            + "If a `callback` is provided, the request will return immediatly with `202 Accepted` and "
            + "the results will be `POST {callback}` as soon as available.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "the analysis results (if no `callback` uri is provided)", response = Analysis.class),
            @ApiResponse(code = 202, message = "accepted (no content). The analysis results are POST'ed to the provided `callback`"),
            @ApiResponse(code = 404, message = "if the conversation is not found or the authenticated user does not have access"),
            @ApiResponse(code = 400, message = "Analysis results are client specific. So a single client MUST BE selected by the request. "
                    + "If the authenticated user is assigend to multiple clients and the " + "parameter `"
                    + PARAM_CLIENT_ID + "` is not specified a `400 Bad Request` is triggered") })
    @RequestMapping(value = "{conversationId}/analysis", method = RequestMethod.GET)
    public ResponseEntity<Analysis> getAnalysis(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId,
            @ApiParam(name = PARAM_CLIENT_ID, required = false, value = DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId,
            @ApiParam(name = PARAM_CALLBACK, required = false, value = DESCRIPTION_PARAM_CALLBACK) @RequestParam(value = PARAM_CALLBACK, required = false) URI callback)
            throws InterruptedException, ExecutionException {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);

        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            client = null;
        }

        final CompletableFuture<Analysis> analysis = analysisService.analyze(client, conversation);
        if (callback == null) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildAnalysisURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                            buildConversationURI(uriBuilder, conversationId)))
                    .body(analysis.get());
        } else {
            analysis.whenComplete((a, e) -> {
                if (a != null) {
                    log.debug("callback {} with {}", callback, a);
                    callbackExecutor.execute(callback, CallbackPayload.success(a));
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", conversation.getId(),
                            callback, e, e.getMessage());
                    log.debug("STACKTRACE: ", e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                            buildAnalysisURI(uriBuilder, conversationId)))
                    .build();
        }
    }

    @ApiOperation(value = "re-run analysis based on updated tokens/slot-assignments", response = Analysis.class, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE, notes = "Allows to re-run the extraction of templates and generation of queries based on a parsed "
            + "analysis. This allows users to remove, reject, confirm or add new Tokens. Those changes"
            + "are considered when updating templates and generating queries. " + API_ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(code = 200, message = "the analysis results (if no `callback` uri is provided)", response = Analysis.class),
            @ApiResponse(code = 202, message = "accepted (no content). The analysis results are POST'ed to the provided `callback`"),
            @ApiResponse(code = 404, message = "if the conversation is not found or the authenticated user does not have access"),
            @ApiResponse(code = 400, message = "Analysis results are client specific. So a single client MUST BE selected by the request. "
                    + "If the authenticated user is assigend to multiple clients and the " + "parameter `"
                    + PARAM_CLIENT_ID + "` is not specified a `400 Bad Request` is triggered") })
    @RequestMapping(value = "{conversationId}/analysis", method = RequestMethod.POST, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rerunAnalysis(AuthContext authContext, @PathVariable("id") ObjectId id,
            @PathVariable("template") int templateIdx, @PathVariable("creator") String creator,
            @ApiParam(hidden = true) @RequestParam(required = false) MultiValueMap<String, String> params,
            @RequestBody QueryUpdate queryUpdate) {
        final Conversation conversation = authenticationService.assertConversation(authContext, id);
        final Client client = authenticationService.assertClient(authContext, conversation.getOwner());

        final Configuration clientConf = configService.getClientConfiguration(client.getId());
        if (clientConf == null) {
            log.info("Client {} of Conversation {} has no longer a configuration assigned ... returning 404 NOT FOUND",
                    conversation.getChannelId(), conversation.getId());
            return ResponseEntity.notFound().build();
        }
        final Template template = conversation.getTemplates().get(templateIdx);
        if (template == null)
            return ResponseEntity.notFound().build();

        //NOTE: conversationService.getConversation(..) already update the queries if necessary
        //so at this place we only need to retrieve the requested query
        Optional<Query> query = template.getQueries().stream().filter(q -> Objects.equals(creator, q.getCreator()))
                .findFirst();
        return query.isPresent() ? ResponseEntity.ok(query.get()) : ResponseEntity.notFound().build();
    }

    @ApiOperation(value = "complete a conversation and add it to indexing", response = Conversation.class)
    @RequestMapping(value = "{id}/publish", method = RequestMethod.POST)
    public ResponseEntity<?> complete(AuthContext authContext, @PathVariable("id") ObjectId id) {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);
        Client client;
        try {
            client = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            client = null;
        }

        CompletableFuture<Analysis> analysis = analysisService.analyze(client, conversation);
        if (callback != null) { //async execution with sync ACCEPTED response
            analysis.whenComplete((a, e) -> {
                if (a != null) {
                    if (a.getTemplates().size() > templateIdx) {
                        log.debug("callback {} with {}", callback, a);
                        callbackExecutor.execute(callback, CallbackPayload.success(a.getTemplates().get(templateIdx)));
                    } else {
                        log.warn(
                                "Template[idx:{}] not present in Analysis of {} containing {} templates. Sending '404 not found' callback to {} ",
                                templateIdx, conversation.getId(), a.getTemplates().size(), callback);
                        callbackExecutor.execute(callback,
                                CallbackPayload.error(new NotFoundException(Template.class, templateIdx)));
                    }
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", conversation.getId(),
                            callback, e, e.getMessage());
                    log.debug("STACKTRACE: ", e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildTemplateURI(uriBuilder, conversationId, templateIdx)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                            buildAnalysisURI(uriBuilder, conversationId)))
                    .build();
        } else { //sync execution
            final List<Template> templates = waitFor(analysis).getTemplates();
            if (templateIdx < templates.size()) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.LINK,
                                String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                        buildTemplateURI(uriBuilder, conversationId, templateIdx)))
                        .header(HttpHeaders.LINK,
                                String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                        buildConversationURI(uriBuilder, conversationId)))
                        .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                                buildAnalysisURI(uriBuilder, conversationId)))
                        .body(templates.get(templateIdx));
            } else {
                throw new NotFoundException(Template.class, templateIdx);
            }
        }

    }

    @ApiOperation(nickname = "getResultsGET", value = "get inline-results for the selected template and query creator", response = InlineSearchResult.class)
    @ApiResponses({ @ApiResponse(code = 200, message = "The search results", response = InlineSearchResult.class), })
    @RequestMapping(value = "{conversationId}/analysis/template/{templateIdx}/result/{creator}", method = RequestMethod.GET)
    public ResponseEntity<?> getResults(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId, @PathVariable("templateIdx") int templateIdx,
            @PathVariable("creator") String creator,
            @ApiParam(name = PARAM_CLIENT_ID, required = false, value = DESCRIPTION_PARAM_CLIENT_ID) @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId)
            throws IOException {
        //just forward to getResults with analysis == null
        return getResults(authContext, uriBuilder, conversationId, templateIdx, creator, null, clientId, null);
    }

    @ApiOperation(nickname = "getResultsPOST", value = "get inline-results for the selected template from the query creator", response = InlineSearchResult.class, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE, notes = "Gets inline results for the selected template based on the parsed analysis object.")
    @ApiResponses({ @ApiResponse(code = 200, message = "The search results", response = InlineSearchResult.class) })
    @RequestMapping(value = "{conversationId}/analysis/template/{templateIdx}/result/{creator}", method = RequestMethod.POST, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getResults(AuthContext authContext,
            @ApiParam(hidden = true) UriComponentsBuilder uriBuilder,
            @PathVariable("conversationId") ObjectId conversationId, @PathVariable("templateIdx") int templateIdx,
            @PathVariable("creator") String creator, @RequestBody Analysis updatedAnalysis,
            @ApiParam @RequestParam(value = PARAM_CLIENT_ID, required = false) ObjectId clientId,
            @ApiParam(DESCRIPTION_PARAM_CALLBACK) @RequestParam(value = PARAM_CALLBACK, required = false) URI callback)
            throws IOException {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);
        Client c;
        try {
            c = getResponseClient(authContext, clientId, conversation);
        } catch (MultipleClientException e) {
            c = null;
        }
        final Client client = c;

        if (templateIdx < 0) {
            return ResponseEntity.badRequest().build();
        }
        CompletableFuture<Analysis> analysis = analysisService.analyze(client, conversation, updatedAnalysis);
        if (callback != null) {
            analysis.whenComplete((a, e) -> {
                if (a != null) {
                    try {
                        SearchResult<? extends Result> result = execcuteQuery(client, conversation, a, templateIdx,
                                creator);
                        log.debug("callback {} with {}", callback, result);
                        callbackExecutor.execute(callback, CallbackPayload.success(result));
                    } catch (RuntimeException | IOException e1) {
                        log.warn(
                                "Execution of Query[client: {}, conversation: {}, template: {}, creator: {}] failed sending error callback to {} ({} - {})",
                                client != null ? client.getId() : null, conversation.getId(), templateIdx, creator,
                                callback, e, e.getMessage());
                        log.debug("STACKTRACE: ", e);
                        callbackExecutor.execute(callback, CallbackPayload.error(e1));
                    }
                } else {
                    log.warn("Analysis of {} failed sending error callback to {} ({} - {})", conversation.getId(),
                            callback, e, e.getMessage());
                    log.debug("STACKTRACE: ", e);
                    callbackExecutor.execute(callback, CallbackPayload.error(e));
                }
            });
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildResultURI(uriBuilder, conversationId, templateIdx, creator)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"template\"",
                                    buildTemplateURI(uriBuilder, conversationId, templateIdx)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .header(HttpHeaders.LINK, String.format(Locale.ROOT, "<%s>; rel=\"analyse\"",
                            buildAnalysisURI(uriBuilder, conversationId)))
                    .build();
        } else {
            return ResponseEntity.ok()
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"self\"",
                                    buildResultURI(uriBuilder, conversationId, templateIdx, creator)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"template\"",
                                    buildTemplateURI(uriBuilder, conversationId, templateIdx)))
                    .header(HttpHeaders.LINK,
                            String.format(Locale.ROOT, "<%s>; rel=\"up\"",
                                    buildConversationURI(uriBuilder, conversationId)))
                    .body(execcuteQuery(client, conversation, getAnalysis(client, conversation), templateIdx, creator));
        }
    }

    conversation.getMeta().setStatus(ConversationMeta.Status.Complete);return ResponseEntity.ok(conversationService.completeConversation(conversation));
}

static class InlineSearchResult extends SearchResult<Result> {

}}
