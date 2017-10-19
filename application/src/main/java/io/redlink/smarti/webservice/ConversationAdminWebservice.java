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
package io.redlink.smarti.webservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.redlink.smarti.model.Client;
import io.redlink.smarti.model.Conversation;
import io.redlink.smarti.model.ConversationMeta;
import io.redlink.smarti.model.Message;
import io.redlink.smarti.model.config.Configuration;
import io.redlink.smarti.services.AuthenticationService;
import io.redlink.smarti.services.ClientService;
import io.redlink.smarti.services.ConversationService;
import io.redlink.smarti.utils.ResponseEntities;
import io.redlink.smarti.utils.WebserviceUtils;
import io.redlink.smarti.webservice.pojo.AuthContext;
import io.redlink.smarti.webservice.pojo.PagedConversationList;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.bson.types.ObjectId;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@CrossOrigin
@RestController
@RequestMapping(value = "admin/conversation",
        produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
@Api("conversation-admin")
public class ConversationAdminWebservice {

    private final ObjectMapper jacksonObjectMapper;
    private final ConversationService conversationService;
    private final ClientService clientService;
    private final AuthenticationService authenticationService;

    public ConversationAdminWebservice(ObjectMapper jacksonObjectMapper, ConversationService conversationService, ClientService clientService, AuthenticationService authenticationService) {
        this.jacksonObjectMapper = jacksonObjectMapper;
        this.conversationService = conversationService;
        this.clientService = clientService;
        this.authenticationService = authenticationService;
    }

    @ApiOperation(value = "list conversations", response = PagedConversationList.class)
    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> listConversations(
            AuthContext authContext,
            @RequestParam(value = "owner", required = false) ObjectId owner,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize
    ) {
        final Client client = authenticationService.assertClient(authContext, owner);

        WebserviceUtils.checkParameter(page >= 0, "page must not be negative");
        WebserviceUtils.checkParameter(pageSize > 0, "minimal pageSize is 1");

        return ResponseEntity.ok(conversationService.listConversations(client.getId(), page, pageSize));
    }

    @ApiOperation(value = "retrieve a conversation", response = Conversation.class)
    @RequestMapping(value = "{conversationId}", method = RequestMethod.GET)
    public ResponseEntity<?> getConversation(
            AuthContext authContext,
            @PathVariable("conversationId") ObjectId conversationId) {
        final Conversation conversation = authenticationService.assertConversation(authContext, conversationId);
        if (Objects.nonNull(conversation)) {
            return ResponseEntity.ok(conversation);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "delete a message", response = Conversation.class)
    @RequestMapping(value = "{conversationId}/message/{messageId}", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteMessage(
            AuthContext authContext,
            @PathVariable("conversationId") ObjectId conversationId,
            @PathVariable("messageId") String messageId) {
        authenticationService.hasAccessToConversation(authContext, conversationId);
        if (conversationService.deleteMessage(conversationId, messageId)) {
            return getConversation(authContext, conversationId);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "edit/update a message", response = Conversation.class)
    @RequestMapping(value = "{conversationId}/message/{messageId}", method = RequestMethod.PUT)
    public ResponseEntity<?> updateMessage(
            AuthContext authContext,
            @PathVariable("conversationId") ObjectId conversationId,
            @PathVariable("messageId") String messageId,
            @RequestBody Message updatedMessage) {
        authenticationService.hasAccessToConversation(authContext, conversationId);

        // Make sure the messageId does not change
        updatedMessage.setId(messageId);
        final Conversation updatedConversation = conversationService.updateMessage(conversationId, updatedMessage);

        if (Objects.nonNull(updatedConversation)) {
            return ResponseEntity.ok(updatedConversation);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "set/update the status of the conversation", response = Conversation.class)
    @RequestMapping(value = "{conversationId}/status/{newStatus}", method = RequestMethod.PUT)
    public ResponseEntity<?> setConversationStatus(
            AuthContext authContext,
            @PathVariable("conversationId") ObjectId conversationId,
            @PathVariable("newStatus") @ApiParam(allowableValues = "New,Ongoing,Complete", required = true) ConversationMeta.Status newStatus) {

        if (authenticationService.hasAccessToConversation(authContext, conversationId)) {
            return ResponseEntity.ok(conversationService.updateStatus(conversationId, newStatus));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @ApiOperation(value = "set/update the status of the conversation", response = Conversation.class)
    @RequestMapping(value = "{conversationId}/expiry", method = RequestMethod.PUT)
    public ResponseEntity<?> setExpiry(
            AuthContext authContext,
            @PathVariable("conversationId") ObjectId conversationId,
            @RequestBody Date expiryDate) {
        // TODO[#59]: implement this
        return ResponseEntities.notImplemented();
    }

    @ApiOperation(value = "export conversations", response = Configuration.class, responseContainer = "List")
    @RequestMapping(value = "export", method = RequestMethod.GET)
    public ResponseEntity<?> exportConversations(
            AuthContext authContext,
            @RequestParam("owner") ObjectId owner) {
        if (authenticationService.hasAccessToClient(authContext, owner)) {
            return ResponseEntity.ok(conversationService.getConversations(owner));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @ApiOperation(value = "import conversations")
    @RequestMapping(value = "import", method = RequestMethod.POST, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importConversations(
            AuthContext authContext,
            @RequestParam("owner") ObjectId owner,
            @RequestParam(value = "replace", defaultValue = "false", required = false) boolean replace,
            @RequestBody List<Conversation> conversations
    ) {
        if (authenticationService.hasAccessToClient(authContext, owner)) {
            conversationService.importConversations(owner, conversations, replace);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @ApiOperation(value = "import conversations")
    @RequestMapping(value = "import", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importConversations(
            AuthContext authContext,
            @RequestParam("owner") ObjectId owner,
            @RequestParam(value = "replace", defaultValue = "false", required = false) boolean replace,
            @RequestParam("file") MultipartFile file
    ) {

        if (authenticationService.hasAccessToClient(authContext, owner)) {
            try {
                final List<Conversation> conversations = jacksonObjectMapper.readValue(file.getInputStream(),
                        jacksonObjectMapper.getTypeFactory().constructCollectionType(List.class, Conversation.class));
                return importConversations(authContext, owner, replace, conversations);
            } catch (IOException e) {
                return ResponseEntity.unprocessableEntity().build();
            }
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

}
