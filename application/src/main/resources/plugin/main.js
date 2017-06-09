require('./style.scss');
$ = require('jquery');
const DDP = require("ddp.js").default;

function Smarti(options) {

    options = $.extend(true,{
        DDP:{
            SocketConstructor: WebSocket
        },
        pollingInterval:5000
    },options);

    var pubsubs = {};

    //init socket connection
    var ddp  = new DDP(options.DDP);

    /**
     * Enabled publication-subscription mechanism
     * @param id
     * @returns {*}
     */
    //taken from http://api.jquery.com/jQuery.Callbacks/
    function pubsub( id ) {
        var callbacks, method,
            pubsub = id && pubsubs[ id ];

        if ( !pubsub ) {
            callbacks = $.Callbacks();
            pubsub = {
                publish: callbacks.fire,
                subscribe: callbacks.add,
                unsubscribe: callbacks.remove
            };
            if ( id ) {
                pubsubs[ id ] = pubsub;
            }
        }
        return pubsub;
    }

    /**
     * Login user for socket communication. If username AND password is provided, the system tries to login with this credentials.
     * If not, the system checks local storage for user tokens and (if token is not expired) logs in the user.
     * @param success method that is called on success
     * @param failure method that is called on failure
     * @param username the username
     * @param password the password
     */
    function login(success,failure,username,password) {

        /**
         * Login to meteor ddp sockets with params
         * @param params
         */
        function loginRequest(params) {
            const loginId = ddp.method("login", params);
            ddp.on("result", function (message) {
                if (message.id == loginId) {

                    if (message.error) return failure({msg:message.error.reason});

                    //TODO fix
                    localStorage.setItem('Meteor.loginToken',message.result.token);
                    localStorage.setItem('Meteor.loginTokenExpires',new Date(message.result.tokenExpires.$date));
                    localStorage.setItem('Meteor.userId',message.result.id);

                    success();
                }
            });
        }

        if(username && password) {

            loginRequest([
                {
                    "user": {"username": username},
                    "password": password
                }, false
            ]);

        } else if(localStorage
            && localStorage.getItem('Meteor.loginToken')
            && localStorage.getItem('Meteor.loginTokenExpires')
            && (new Date(localStorage.getItem('Meteor.loginTokenExpires')) > new Date())) {

            console.log('found token %s for user %s that expires on %s',
                localStorage.getItem('Meteor.loginToken'),
                localStorage.getItem('Meteor.userId'),
                localStorage.getItem('Meteor.loginTokenExpires')
            );

            loginRequest([
                { "resume": localStorage.getItem('Meteor.loginToken') }
            ]);

        } else {
            failure({msg:'No auth token or token expired'});
        }
    }

    function load(success,failure) {
        $.getJSON('https://dev.cerbot.redlink.io/9503/data/intent.json',function(data){ //TODO get from server
            success(data);
        });
    }

    //TODO should be done with sockets in version II
    function poll() {
        /*$.ajax({
            url: options.smarti.pollingEndpoint,
            data: {channel:options.channel},
            success: function(data){
                //TODO constraints
                console.log(data);
                pubsub('smarti.data').publish(data)
            },
            dataType: "json",
            complete: function() {
                //TODO uncomment
                //setTimeout(poll,options.pollingInterval)
            },
            timeout: 30000
        });*/
    }

    poll();

    /*function connect(roomId, success, failure) {

        function listRooms() {
            const methodId = ddp.method("rooms/get");
            ddp.on("result", function(message) {
                if(message.id == methodId)
                    console.log(message);
                subscribe("GENERAL");
            });
        }

        function subscribe(roomid) {
            const subId = ddp.sub("stream-room-messages",[roomid,false]);console.log(subId);
            ddp.on("ready", function(message) {
                if (message.subs.includes(subId)) {
                    console.log(message);
                }
            });
        }

        ddp.on("added", function(message) {
            console.log(message);
        });

        ddp.on("changed", function(message) {
            console.log(message);
        });

        ddp.on("removed", function(message) {
            console.log(message);
        });

    }*/

    function postMessage(msg,attachments,success,error) {
        const methodId = ddp.method("sendMessage",[{rid:options.channel,msg:msg,attachments:attachments}]);
        ddp.on("result", function(message) {
            if(message.id == methodId) {
                if(message.error && error) error(message.error);
                else if(success) success();
            }
        });
    }

    function suggestMessage(msg) {

    }

    return {
        login: login,
        load: load,
        subscribe: function(id,func){pubsub(id).subscribe(func)},
        unsubscribe: function(id,func){pubsub(id).unsubscribe(func)},
        postMessage: postMessage,
        suggestMessage: suggestMessage
    }
}

function SmartiWidget(element,channel,config) {

    var widgets = {
        solr:[],
        conversations:[]
    };

    function SolrWidget(elem,slots,tokens,query) {

        elem.append('<h2>' + query.displayTitle + '</h2>');
        var content = $('<div>').appendTo(elem);

        function createTermPill(token) {
            return $('<div class="smarti-token-pill">')
                .append($('<span>').text(token.value))
                .append('<i class="icon-cancel"></i>')
                .data('token',token)
                .click(function(){
                    $(this).remove();
                    getResults();
                });
        }

        var termPills = $('<div class="smarti-token-pills">').appendTo(content);

        $.each(slots,function(i,slot){
            if(slot.tokenIndex != undefined && slot.tokenIndex > -1) {
                termPills.append(createTermPill(tokens[slot.tokenIndex]));
            } else if(!slot.tokenIndex) {
                termPills.append(createTermPill(slot.token));
            }
        });

        function refresh(data) {

        }

        var inputForm = $('<div class="search-form" role="form"><div class="input-line search"><input type="text" class="search content-background-color" placeholder="Weiter Suchterme" autocomplete="off"> <i class="icon-search secondary-font-color"></i> </div></div>');
        var inputField = inputForm.find('input');

        inputField.keypress(function(e){
            if(e.which == 13) {
                var val = $(this).val();
                if(val!= undefined && val != "") {
                    termPills.append(createTermPill({
                        origin:'User',
                        value:val,
                        type:'Keyword'
                    }));
                    $(this).val("");
                }
                getResults();
            }
        });

        elem.append(inputForm);
        var resultCount = $('<h3></h3>').appendTo(elem);
        var loader = $('<div class="loading-animation"> <div class="bounce1"></div> <div class="bounce2"></div> <div class="bounce3"></div> </div>').hide().appendTo(elem);
        var results = $('<ul class="search-results">').appendTo(elem);

        function getResults() {
            var tks = termPills.children().map(function(){return $(this).data().token.value}).get().join(" ");
            //TODO get query string from remote
            results.empty();
            resultCount.empty();
            loader.show();
            $.getJSON("https://dev.cerbot.redlink.io/9503/data/solr-response.json", function(data){
                loader.hide();
                //hacky
                var docs = $.map(data.response.docs.slice(0,3), function(doc) {
                    return {
                        source: doc.dbsearch_source_name_s + '/' + doc.dbsearch_space_name_t,
                        title: doc.dbsearch_title_s,
                        description: doc.dbsearch_excerpt_s,
                        type: doc.dbsearch_doctype_s,
                        doctype: doc.dbsearch_content_type_aggregated_s.slice(0,4),
                        link: doc.dbsearch_link_s,
                        date: new Date(doc.dbsearch_pub_date_tdt)
                    }
                });

                resultCount.text('Top 3 von ' + data.response.numFound);

                $.each(docs,function(i,doc){
                    var docli = $('<li>' +
                        '<div class="result-type result-type-'+doc.doctype+'"><div>'+doc.doctype+'</div></div>' +
                        '<div class="result-content"><div class="result-content-title"><a href="'+doc.link+'" target="blank">'+doc.title+'</a><span>'+doc.date.toLocaleDateString()+'</span></div><p>'+doc.description+'</p></div>' +
                        '<div class="result-actions"><button class="postAnswer">Posten<i class="icon-paper-plane"></i></button></div>'+
                        (i+1 != docs.length ? '<li class="result-separator"><div></div></li>':'') +
                        '</li>');

                    docli.find('.postAnswer').click(function(){
                        var text = "Das habe ich dazu in " + query.creator + " gefunden.";
                        var attachments = [{
                            title: doc.title,
                            title_link: doc.link,
                            thumb_url:'http://www.s-bahn-berlin.de/img/logo-db.png',//TODO should be per creator
                            text:doc.description
                        }];
                        smarti.postMessage(text,attachments);
                    });

                    results.append(docli);
                })
            })
        }

        getResults();

        return {
            refresh: refresh
        }
    }

    function ConversationWidget(elem,slots,tokens,query) {

        elem.append('<h2>' + query.displayTitle + '</h2>');

        function refresh(data) {

        }

        var loader = $('<div class="loading-animation"> <div class="bounce1"></div> <div class="bounce2"></div> <div class="bounce3"></div> </div>').hide().appendTo(elem);
        var results = $('<ul class="search-results">').appendTo(elem);

        function getResults() {
            //TODO get remote
            results.empty();
            loader.show();
            $.getJSON("https://dev.cerbot.redlink.io/9503/data/solr-response.json", function(data){
                loader.hide();
                //hacky
                var docs = $.map(data.response.docs.slice(0,3), function(doc) {
                    return {
                        source: doc.dbsearch_source_name_s + '/' + doc.dbsearch_space_name_t,
                        title: doc.dbsearch_title_s,
                        description: doc.dbsearch_excerpt_s,
                        type: doc.dbsearch_doctype_s,
                        doctype: doc.dbsearch_content_type_aggregated_s.slice(0,4),
                        link: doc.dbsearch_link_s,
                        date: new Date(doc.dbsearch_pub_date_tdt)
                    }
                });

                $.each(docs,function(i,doc){
                    var docli = $('<li>' +
                        '<div class="result-type result-type-'+doc.doctype+'"><div>'+doc.doctype+'</div></div>' +
                        '<div class="result-content"><div class="result-content-title"><a href="'+doc.link+'" target="blank">'+doc.title+'</a><span>'+doc.date.toLocaleDateString()+'</span></div><p>'+doc.description+'</p></div>' +
                        '<div class="result-actions"><button class="postAnswer">Posten<i class="icon-paper-plane"></i></button></div>'+
                        (i+1 != docs.length ? '<li class="result-separator"><div></div></li>':'') +
                        '</li>');

                    docli.find('.postAnswer').click(function(){
                        var text = "Das habe ich dazu in " + query.creator + " gefunden.";
                        var attachments = [{
                            title: doc.title,
                            title_link: doc.link,
                            thumb_url:'http://www.s-bahn-berlin.de/img/logo-db.png',//TODO should be per creator
                            text:doc.description
                        }];
                        smarti.postMessage(text,attachments);
                    });

                    results.append(docli);
                })
            })
        }

        getResults();

        return {
            refresh: refresh
        }
    }

    element = $(element);

    $('<div class="title"> <h2>Smarti</h2> </div>').appendTo(element.empty());
    var mainDiv = $('<div>').addClass('widget').appendTo(element);


    var contentDiv = $('<div>').appendTo(mainDiv);
    var messagesDiv = $('<div>').appendTo(mainDiv);

    var options = {
        socketEndpoint: "wss://rocket.redlink.io/websocket",
        pollingEndpoint: 'https://echo.jsontest.com/key/value',
        channel:channel || 'GENERAL'
    };

    var initialized = false;

    var smarti = Smarti({DDP:{endpoint:options.socketEndpoint},smarti:{pollingEndpoint:options.pollingEndpoint},channel:options.channel});//TODO wait for connect?

    smarti.subscribe('smarti.data', function(data){
        if(initialized) refreshWidgets(data);
    });

    function showError(err) {
        messagesDiv.empty().append($('<p>').text(err.msg));
    }

    function drawLogin() {

        contentDiv.empty();

        var form = $('<form><span>Username</span><input type="text"><br><span>Password</span><input type="password"><br><button>Submit</button></form>');

        form.find('button').click(function(){

            var username = form.find('input[type="text"]').val();
            var password = form.find('input[type="password"]').val();

            smarti.login(
                initialize,
                showError,
                username,
                password
            );

            return false;
        });

        form.appendTo(contentDiv);

    }

    function refreshWidgets(data) {
        //TODO
    }

    function drawWidgets(data,success) {

        contentDiv.empty();
        messagesDiv.empty();

        $.each(data.templates, function(i, template){
            $.each(template.queries, function(j, query) {
                switch(template.type) {
                    case 'dbsearch': widgets.solr.push(new SolrWidget($('<div class="smarti-widget">').appendTo(contentDiv),template.slots,data.tokens,query));break;
                    case 'related.conversation': widgets.solr.push(new ConversationWidget($('<div class="smarti-widget">').appendTo(contentDiv),template.slots,data.tokens,query));break;
                }
            })
        });

        if(success) success();
    }

    function initialize() {
        smarti.load(
            function(data) {
                drawWidgets(data, function(){
                    initialized = true;
                })
            }
        )
    }

    smarti.login(
        initialize,
        drawLogin
    );

    function reload() {
        //TODO
    }

    return {
        reload:reload
    };
}

window.SmartiWidget = SmartiWidget;
