package io.redlink.smarti.query.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.redlink.smarti.api.StoreService;
import io.redlink.smarti.model.Client;
import io.redlink.smarti.util.SearchUtils;
import io.redlink.solrlib.SolrCoreContainer;
import io.redlink.solrlib.SolrCoreDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.NoOpResponseParser;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.GroupParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.redlink.smarti.query.conversation.ConversationIndexConfiguration.FIELD_OWNER;
import static io.redlink.smarti.query.conversation.ConversationIndexConfiguration.FIELD_TYPE;
import static io.redlink.smarti.query.conversation.ConversationIndexConfiguration.TYPE_MESSAGE;

@Service
public class MessageSearchService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final SolrCoreContainer solrServer;
    private final SolrCoreDescriptor conversationCore;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    public MessageSearchService(SolrCoreContainer solrServer, @Qualifier(ConversationIndexConfiguration.CONVERSATION_INDEX) SolrCoreDescriptor conversationCore) {
        this.solrServer = solrServer;
        this.conversationCore = conversationCore;
    }

    public Object search(Client client, Map<String, String[]> requestParameterMap) throws IOException {
        final ModifiableSolrParams solrParams = new ModifiableSolrParams(new HashMap<>(requestParameterMap));

        solrParams.add(CommonParams.FQ, String.format("%s:\"%s\"", FIELD_OWNER, client.getId().toHexString()));
        solrParams.add(CommonParams.FQ, String.format("%s:\"%s\"", FIELD_TYPE, TYPE_MESSAGE));

        log.trace("SolrParams: {}", solrParams);

        try (SolrClient solrClient = solrServer.getSolrClient(conversationCore)) {

            QueryResponse response = solrClient.query(solrParams);

            Map map = response.getResponse().asMap(Integer.MAX_VALUE);

            map.put("meta", ImmutableMap.of("numFound", response.getResults().getNumFound(), "start", response.getResults().getStart()));

            return map;

        } catch (SolrServerException e) {
            throw new IllegalStateException("Cannot query non-initialized core", e);
        }
    }

}
