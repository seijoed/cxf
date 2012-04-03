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

package org.apache.cxf.rs.security.oauth2.services;

import java.util.List;

import javax.ws.rs.Path;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.ServerAccessToken;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeDataProvider;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeRegistration;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;


/**
 * This resource handles the End User authorising
 * or denying the Client to access its resources.
 * If End User approves the access this resource will
 * redirect End User back to the Client, supplying 
 * the authorization code.
 */
@Path("/authorize")
public class AuthorizationCodeGrantService extends RedirectionBasedGrantService {
    public AuthorizationCodeGrantService() {
        super(OAuthConstants.CODE_RESPONSE_TYPE, OAuthConstants.AUTHORIZATION_CODE_GRANT, true);
    }
    
    protected Response createGrant(MultivaluedMap<String, String> params,
                                   Client client,
                                   String redirectUri,
                                   List<String> requestedScope,
                                   List<String> approvedScope,
                                   UserSubject userSubject,
                                   ServerAccessToken preauthorizedToken) {
        // in this flow the code is still created, the preauthorized token
        // will be retrieved by the authorization code grant handler
        AuthorizationCodeRegistration codeReg = new AuthorizationCodeRegistration(); 
        
        codeReg.setClient(client);
        codeReg.setRedirectUri(redirectUri);
        codeReg.setRequestedScope(requestedScope);
        codeReg.setApprovedScope(approvedScope);
        codeReg.setSubject(userSubject);
        
        ServerAuthorizationCodeGrant grant = null;
        try {
            grant = ((AuthorizationCodeDataProvider)getDataProvider()).createCodeGrant(codeReg);
        } catch (OAuthServiceException ex) {
            return createErrorResponse(params, redirectUri, OAuthConstants.ACCESS_DENIED);
        }
        
        // return the code by appending it as a query parameter to the redirect URI
        UriBuilder ub = getRedirectUriBuilder(params.getFirst(OAuthConstants.STATE), redirectUri);
        ub.queryParam(OAuthConstants.AUTHORIZATION_CODE_VALUE, grant.getCode());
        return Response.seeOther(ub.build()).build();
    }
    
    protected Response createErrorResponse(MultivaluedMap<String, String> params,
                                           String redirectUri,
                                           String error) {
        UriBuilder ub = getRedirectUriBuilder(params.getFirst(OAuthConstants.STATE), redirectUri);
        ub.queryParam(OAuthConstants.ERROR_KEY, error);
        return Response.seeOther(ub.build()).build();
    }
    
    protected UriBuilder getRedirectUriBuilder(String state, String redirectUri) {
        UriBuilder ub = UriBuilder.fromUri(redirectUri);
        if (state != null) { 
            ub.queryParam(OAuthConstants.STATE, state);
        }
        return ub;
    }
}


