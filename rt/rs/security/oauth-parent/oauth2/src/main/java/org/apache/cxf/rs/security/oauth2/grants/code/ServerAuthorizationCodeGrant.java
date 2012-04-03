/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.oauth2.grants.code;

import java.util.Collections;
import java.util.List;

import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.utils.OAuthUtils;


/**
 * The Authorization Code Grant representation visible to the server
 */
public class ServerAuthorizationCodeGrant extends AuthorizationCodeGrant {
    private long issuedAt;
    private long lifetime;
    private Client client;
    private List<String> approvedScopes = Collections.emptyList();
    private UserSubject subject;
    
    public ServerAuthorizationCodeGrant(Client client, 
                                        long lifetime) {
        this(client, OAuthUtils.generateRandomTokenKey(), lifetime,
             System.currentTimeMillis() / 1000);
    }
    
    public ServerAuthorizationCodeGrant(Client client, 
                                  String code,
                                  long lifetime, 
                                  long issuedAt) {
        super(code);
        this.client = client;
        this.lifetime = lifetime;
        this.issuedAt = issuedAt;
    }

    /**
     * Returns the time (in seconds) this grant was issued at
     * @return the seconds
     */
    public long getIssuedAt() {
        return issuedAt;
    }

    /**
     * Returns the number of seconds this grant can be valid after it was issued
     * @return the seconds this grant will be valid for
     */
    public long getLifetime() {
        return lifetime;
    }

    /**
     * Returns the reference to {@link Client}
     * @return the client
     */
    public Client getClient() {
        return client;
    }

    /**
     * Sets the scopes explicitly approved by the end user.
     * If this list is empty then the end user had no way to down-scope. 
     * @param approvedScope the approved scopes
     */
    
    public void setApprovedScopes(List<String> scopes) {
        this.approvedScopes = scopes;
    }

    /**
     * Gets the scopes explicitly approved by the end user
     * @return the approved scopes
     */
    
    public List<String> getApprovedScopes() {
        return approvedScopes;
    }


    /**
     * Sets the user subject representing the end user
     * @param subject the subject
     */
    public void setSubject(UserSubject subject) {
        this.subject = subject;
    }
    
    /**
     * Gets the user subject representing the end user
     * @return the subject
     */
    public UserSubject getSubject() {
        return subject;
    }
}
