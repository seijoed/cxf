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
package org.apache.cxf.sts.token.renewer;

import java.util.Date;
import java.util.Properties;

import javax.security.auth.callback.CallbackHandler;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.apache.cxf.jaxws.context.WebServiceContextImpl;
import org.apache.cxf.jaxws.context.WrappedMessageContext;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.sts.STSConstants;
import org.apache.cxf.sts.StaticSTSProperties;
import org.apache.cxf.sts.cache.DefaultInMemoryTokenStore;
import org.apache.cxf.sts.common.PasswordCallbackHandler;
import org.apache.cxf.sts.request.KeyRequirements;
import org.apache.cxf.sts.request.Lifetime;
import org.apache.cxf.sts.request.ReceivedToken;
import org.apache.cxf.sts.request.ReceivedToken.STATE;
import org.apache.cxf.sts.request.TokenRequirements;
import org.apache.cxf.sts.service.EncryptionProperties;
import org.apache.cxf.sts.token.provider.DefaultConditionsProvider;
import org.apache.cxf.sts.token.provider.SAMLTokenProvider;
import org.apache.cxf.sts.token.provider.TokenProviderParameters;
import org.apache.cxf.sts.token.provider.TokenProviderResponse;
import org.apache.cxf.sts.token.validator.SAMLTokenValidator;
import org.apache.cxf.sts.token.validator.TokenValidator;
import org.apache.cxf.sts.token.validator.TokenValidatorParameters;
import org.apache.cxf.sts.token.validator.TokenValidatorResponse;
import org.apache.cxf.ws.security.sts.provider.STSException;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.ws.security.CustomTokenPrincipal;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoFactory;
import org.apache.ws.security.util.XmlSchemaDateFormat;
import org.junit.BeforeClass;

/**
 * Some unit tests for renewing a SAML token via the SAMLTokenRenewer.
 */
public class SAMLTokenRenewerTest extends org.junit.Assert {
    
    private static TokenStore tokenStore;
    
    @BeforeClass
    public static void init() {
        tokenStore = new DefaultInMemoryTokenStore();
    }
    
    /**
     * Renew an expired SAML1 Assertion
     */
    @org.junit.Test
    public void renewExpiredSAML1Assertion() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        // Sleep to expire the token
        Thread.sleep(1000);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.EXPIRED);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        TokenRenewerResponse renewerResponse = 
                samlTokenRenewer.renewToken(renewerParameters);
        assertTrue(renewerResponse != null);
        assertTrue(renewerResponse.getToken() != null);
        
        // Now validate it again
        validateTarget = new ReceivedToken(renewerResponse.getToken());
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        validatorResponse = samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
    }
    
    /**
     * Renew an expired SAML1 Assertion without using the cache
     */
    @org.junit.Test
    public void renewExpiredSAML1AssertionNoCache() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        // Sleep to expire the token
        Thread.sleep(1000);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        validatorParameters.setTokenStore(null);
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.EXPIRED);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        TokenRenewerResponse renewerResponse = 
                samlTokenRenewer.renewToken(renewerParameters);
        assertTrue(renewerResponse != null);
        assertTrue(renewerResponse.getToken() != null);
        
        // Now validate it again
        validateTarget = new ReceivedToken(renewerResponse.getToken());
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        validatorResponse = samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
    }
    
    /**
     * Renew an expired SAML2 Assertion
     */
    @org.junit.Test
    public void renewExpiredSAML2Assertion() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML2_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        // Sleep to expire the token
        Thread.sleep(1000);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.EXPIRED);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        TokenRenewerResponse renewerResponse = 
                samlTokenRenewer.renewToken(renewerParameters);
        assertTrue(renewerResponse != null);
        assertTrue(renewerResponse.getToken() != null);
        
        // Now validate it again
        validateTarget = new ReceivedToken(renewerResponse.getToken());
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        validatorResponse = samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
    }
    
    /**
     * Renew an expired SAML2 Assertion without using the cache
     */
    @org.junit.Test
    public void renewExpiredSAML2AssertionNoCache() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML2_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        // Sleep to expire the token
        Thread.sleep(1000);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        validatorParameters.setTokenStore(null);
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.EXPIRED);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        TokenRenewerResponse renewerResponse = 
                samlTokenRenewer.renewToken(renewerParameters);
        assertTrue(renewerResponse != null);
        assertTrue(renewerResponse.getToken() != null);
        
        // Now validate it again
        validateTarget = new ReceivedToken(renewerResponse.getToken());
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        validatorResponse = samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
    }
    
    /**
     * Renew a valid SAML1 Assertion
     */
    @org.junit.Test
    public void renewValidSAML1Assertion() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50000);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        TokenRenewerResponse renewerResponse = 
                samlTokenRenewer.renewToken(renewerParameters);
        assertTrue(renewerResponse != null);
        assertTrue(renewerResponse.getToken() != null);
        
        // Now validate it again
        validateTarget = new ReceivedToken(renewerResponse.getToken());
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        validatorResponse = samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.VALID);
    }
    
    
    /**
     * Renew an expired SAML2 Assertion that has expired greater than the maximum allowable time
     * for renewal.
     */
    @org.junit.Test
    public void renewTooFarExpiredSAML2Assertion() throws Exception {
        // Create the Assertion
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        CallbackHandler callbackHandler = new PasswordCallbackHandler();
        Element samlToken = 
            createSAMLAssertion(WSConstants.WSS_SAML2_TOKEN_TYPE, crypto, "mystskey", callbackHandler, 50);
        Document doc = samlToken.getOwnerDocument();
        samlToken = (Element)doc.appendChild(samlToken);
        // Sleep to expire the token
        Thread.sleep(2000);
        
        // Validate the Assertion
        TokenValidator samlTokenValidator = new SAMLTokenValidator();
        TokenValidatorParameters validatorParameters = createValidatorParameters();
        TokenRequirements tokenRequirements = validatorParameters.getTokenRequirements();
        ReceivedToken validateTarget = new ReceivedToken(samlToken);
        tokenRequirements.setValidateTarget(validateTarget);
        validatorParameters.setToken(validateTarget);
        
        assertTrue(samlTokenValidator.canHandleToken(validateTarget));
        
        TokenValidatorResponse validatorResponse = 
                samlTokenValidator.validateToken(validatorParameters);
        assertTrue(validatorResponse != null);
        assertTrue(validatorResponse.getToken() != null);
        assertTrue(validatorResponse.getToken().getState() == STATE.EXPIRED);
        
        // Renew the Assertion
        TokenRenewerParameters renewerParameters = new TokenRenewerParameters();
        renewerParameters.setAppliesToAddress("http://dummy-service.com/dummy");
        renewerParameters.setStsProperties(validatorParameters.getStsProperties());
        renewerParameters.setPrincipal(new CustomTokenPrincipal("alice"));
        renewerParameters.setWebServiceContext(validatorParameters.getWebServiceContext());
        renewerParameters.setKeyRequirements(validatorParameters.getKeyRequirements());
        renewerParameters.setTokenRequirements(validatorParameters.getTokenRequirements());
        renewerParameters.setTokenStore(validatorParameters.getTokenStore());
        renewerParameters.setToken(validatorResponse.getToken());
        
        TokenRenewer samlTokenRenewer = new SAMLTokenRenewer();
        samlTokenRenewer.setVerifyProofOfPossession(false);
        ((SAMLTokenRenewer)samlTokenRenewer).setMaxExpiry(1L);
        assertTrue(samlTokenRenewer.canHandleToken(validatorResponse.getToken()));
        
        try {
            samlTokenRenewer.renewToken(renewerParameters);
            fail("Failure expected as the token expired too long ago");
        } catch (STSException ex) {
            // Expected
        }
    }

    private TokenValidatorParameters createValidatorParameters() throws WSSecurityException {
        TokenValidatorParameters parameters = new TokenValidatorParameters();
        
        TokenRequirements tokenRequirements = new TokenRequirements();
        parameters.setTokenRequirements(tokenRequirements);
        
        KeyRequirements keyRequirements = new KeyRequirements();
        parameters.setKeyRequirements(keyRequirements);
        
        parameters.setPrincipal(new CustomTokenPrincipal("alice"));
        // Mock up message context
        MessageImpl msg = new MessageImpl();
        WrappedMessageContext msgCtx = new WrappedMessageContext(msg);
        WebServiceContextImpl webServiceContext = new WebServiceContextImpl(msgCtx);
        parameters.setWebServiceContext(webServiceContext);
        
        // Add STSProperties object
        StaticSTSProperties stsProperties = new StaticSTSProperties();
        Crypto crypto = CryptoFactory.getInstance(getEncryptionProperties());
        stsProperties.setEncryptionCrypto(crypto);
        stsProperties.setSignatureCrypto(crypto);
        stsProperties.setEncryptionUsername("myservicekey");
        stsProperties.setSignatureUsername("mystskey");
        stsProperties.setCallbackHandler(new PasswordCallbackHandler());
        stsProperties.setIssuer("STS");
        parameters.setStsProperties(stsProperties);
        parameters.setTokenStore(tokenStore);
        
        return parameters;
    }
    
    private Element createSAMLAssertion(
            String tokenType, Crypto crypto, String signatureUsername,
            CallbackHandler callbackHandler, long ttlMs
    ) throws WSSecurityException {
        SAMLTokenProvider samlTokenProvider = new SAMLTokenProvider();
        DefaultConditionsProvider conditionsProvider = new DefaultConditionsProvider();
        conditionsProvider.setAcceptClientLifetime(true);
        samlTokenProvider.setConditionsProvider(conditionsProvider);
        TokenProviderParameters providerParameters = 
            createProviderParameters(
                    tokenType, STSConstants.BEARER_KEY_KEYTYPE, crypto, signatureUsername, callbackHandler
            );

        if (ttlMs != 0) {
            Lifetime lifetime = new Lifetime();
            Date creationTime = new Date();
            Date expirationTime = new Date();
            expirationTime.setTime(creationTime.getTime() + ttlMs);

            XmlSchemaDateFormat fmt = new XmlSchemaDateFormat();
            lifetime.setCreated(fmt.format(creationTime));
            lifetime.setExpires(fmt.format(expirationTime));

            providerParameters.getTokenRequirements().setLifetime(lifetime);
        }

        TokenProviderResponse providerResponse = samlTokenProvider.createToken(providerParameters);
        assertTrue(providerResponse != null);
        assertTrue(providerResponse.getToken() != null && providerResponse.getTokenId() != null);

        return providerResponse.getToken();
    }    
    
    private TokenProviderParameters createProviderParameters(
        String tokenType, String keyType, Crypto crypto, 
        String signatureUsername, CallbackHandler callbackHandler
    ) throws WSSecurityException {
        TokenProviderParameters parameters = new TokenProviderParameters();

        TokenRequirements tokenRequirements = new TokenRequirements();
        tokenRequirements.setTokenType(tokenType);
        parameters.setTokenRequirements(tokenRequirements);

        KeyRequirements keyRequirements = new KeyRequirements();
        keyRequirements.setKeyType(keyType);
        parameters.setKeyRequirements(keyRequirements);

        parameters.setPrincipal(new CustomTokenPrincipal("alice"));
        // Mock up message context
        MessageImpl msg = new MessageImpl();
        WrappedMessageContext msgCtx = new WrappedMessageContext(msg);
        WebServiceContextImpl webServiceContext = new WebServiceContextImpl(msgCtx);
        parameters.setWebServiceContext(webServiceContext);

        parameters.setAppliesToAddress("http://dummy-service.com/dummy");

        // Add STSProperties object
        StaticSTSProperties stsProperties = new StaticSTSProperties();
        stsProperties.setSignatureCrypto(crypto);
        stsProperties.setSignatureUsername(signatureUsername);
        stsProperties.setCallbackHandler(callbackHandler);
        stsProperties.setIssuer("STS");
        parameters.setStsProperties(stsProperties);

        parameters.setEncryptionProperties(new EncryptionProperties());
        parameters.setTokenStore(tokenStore);
        
        return parameters;
    }
    
    private Properties getEncryptionProperties() {
        Properties properties = new Properties();
        properties.put(
            "org.apache.ws.security.crypto.provider", "org.apache.ws.security.components.crypto.Merlin"
        );
        properties.put("org.apache.ws.security.crypto.merlin.keystore.password", "stsspass");
        properties.put("org.apache.ws.security.crypto.merlin.keystore.file", "stsstore.jks");
        
        return properties;
    }
    
    
}
