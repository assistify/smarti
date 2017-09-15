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

import io.redlink.smarti.auth.AttributedUserDetails;
import io.redlink.smarti.auth.mongo.MongoAuthConfiguration;
import io.redlink.smarti.auth.mongo.MongoUserDetails;
import io.redlink.smarti.auth.mongo.MongoUserDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@ConditionalOnBean(MongoUserDetailsService.class)
public class AccountService {

    private Logger log = LoggerFactory.getLogger(AccountService.class);

    private final MongoAuthConfiguration.PasswordEncoder passwordEncoder;
    private final MongoUserDetailsService userDetailsService;

    public AccountService(MongoUserDetailsService userDetailsService, MongoAuthConfiguration.PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
    }

    public AttributedUserDetails createAccount(String userName, String mail) {
        return createAccount(userName, mail, null);
    }

    public AttributedUserDetails createAccount(String userName, String mail, String password) {
        MongoUserDetailsService.MongoUser user = new MongoUserDetailsService.MongoUser();
        user.setUsername(userName);
        user.setPassword(passwordEncoder.encodePassword(password));
        user.setRoles(Collections.emptySet());
        user.setAttributes(Collections.singletonMap(AttributedUserDetails.ATTR_EMAIL, mail));

        return userDetailsService.createUserDetail(user);
    }

    public void startPasswordRecovery(String userName) {
        final MongoUserDetailsService.PasswordRecovery recoveryToken = userDetailsService.createPasswordRecoveryToken(userName);
        // TODO: Send a mail
        log.info("Created password recovery token for user '{}': '{}', valid until {}", userName, recoveryToken.getToken(), recoveryToken.getExpires());
    }

    public boolean completePasswordRecovery(String userName, String newPasswd, String recoveryToken) {
        return userDetailsService.updatePassword(userName, passwordEncoder.encodePassword(newPasswd), recoveryToken);
    }

    public boolean hasAccount(String userName) {
        return userDetailsService.loadUserByUsername(userName) != null;
    }
}
