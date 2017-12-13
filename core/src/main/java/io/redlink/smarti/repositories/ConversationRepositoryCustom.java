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

package io.redlink.smarti.repositories;

import io.redlink.smarti.model.Conversation;
import io.redlink.smarti.model.ConversationMeta;
import io.redlink.smarti.model.Message;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Transient;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Custom repository for Conversations
 *
 * @author Sergio Fernandez
 */
public interface ConversationRepositoryCustom {

    List<ObjectId> findConversationIDs();

    List<ObjectId> findConversationIDsByUser(String userId);

    List<String> findTagsByPattern(Pattern pattern, int limit);

    List<Pair<String, Long>> findTags(long limit);

    List<Pair<String, Long>> findTags(long limit, long offset);

    ObjectId findCurrentConversationIDByChannelID(String channelId);

    Conversation appendMessage(Conversation conversation, Message message);

    Conversation updateMessage(ObjectId conversationId, Message updatedMessage);

    Conversation saveIfNotLastModifiedAfter(Conversation finalConversation, Date lastModified);

    Conversation completeConversation(ObjectId conversationId);

    Conversation adjustMessageVotes(ObjectId conversationId, String messageId, int delta);

    Conversation updateConversationStatus(ObjectId conversationId, ConversationMeta.Status status);

    boolean deleteMessage(ObjectId conversationId, String messageId);
    /**
     * Provides the ids of entities that where updated
     * since the parsed date. In addition it provides the
     * date of the latest update (to be used by further calls)
     * @param date the date since updates should be returned
     * @return the updated entities and the date of the last update
     */
    @Transient
    UpdatedConversationIds updatedSince(Date date);
    
    
    public static class UpdatedConversationIds {
        
        private final Date lastModified;
        final private List<ObjectId> ids;
        
        public UpdatedConversationIds(Date lastModified, List<ObjectId> ids){
            this.ids = ids;
            this.lastModified = lastModified;
        }
        
        public Date getLastModified() {
            return lastModified;
        }
        
        public List<ObjectId> ids(){
            return ids;
        }

        @Override
        public String toString() {
            return "UpdatedConversationIds [lastModified=" + lastModified + ", ids=" + ids + "]";
        }
        
        
    }
}
