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

package org.apache.cxf.ws.policy;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.xml.namespace.QName;

import org.apache.cxf.Bus;
import org.apache.cxf.common.injection.NoJSR250Annotations;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.configuration.ConfiguredBeanLocator;
import org.apache.cxf.extension.BusExtension;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.service.factory.FactoryBeanListenerManager;
import org.apache.cxf.service.model.BindingFaultInfo;
import org.apache.cxf.service.model.BindingMessageInfo;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.service.model.ServiceInfo;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.Destination;
import org.apache.cxf.ws.policy.selector.MinimalAlternativeSelector;
import org.apache.neethi.Constants;
import org.apache.neethi.Policy;
import org.apache.neethi.PolicyComponent;
import org.apache.neethi.PolicyOperator;
import org.apache.neethi.PolicyReference;
import org.apache.neethi.PolicyRegistry;

/**
 * 
 */
@NoJSR250Annotations(unlessNull = "bus")
public class PolicyEngineImpl implements PolicyEngine, BusExtension {
    private static final Logger LOG = LogUtils.getL7dLogger(PolicyEngineImpl.class);
    
    
    private static final String POLICY_INFO_REQUEST_SERVER = "policy-engine-info-serve-request";
    private static final String POLICY_INFO_FAULT_SERVER = "policy-engine-info-serve-fault";
    private static final String POLICY_INFO_RESPONSE_SERVER = "policy-engine-info-serve-response";
    private static final String POLICY_INFO_ENDPOINT_SERVER = "policy-engine-info-serve-rendpoint";
    
    private static final String POLICY_INFO_REQUEST_CLIENT = "policy-engine-info-client-request";
    private static final String POLICY_INFO_FAULT_CLIENT = "policy-engine-info-client-fault";
    private static final String POLICY_INFO_RESPONSE_CLIENT = "policy-engine-info-client-response";
    private static final String POLICY_INFO_ENDPOINT_CLIENT = "policy-engine-info-client-endpoint";
    
    private Bus bus;
    private PolicyRegistry registry;
    private Collection<PolicyProvider> policyProviders;
    private boolean enabled = true;
    private Boolean ignoreUnknownAssertions;
    private boolean addedBusInterceptors;
    private AlternativeSelector alternativeSelector;


    public PolicyEngineImpl() { 
        init();
    }
    public PolicyEngineImpl(boolean en) {
        enabled = en;
        init();
    }
    public PolicyEngineImpl(Bus b) {
        init();
        setBus(b);        
    }

    // configuration

    public boolean isEnabled() {
        return enabled;
    }

    @Resource
    public final void setBus(Bus b) {
        if (this.bus == b) {
            //avoid bus init twice through injection
            return;
        }
        bus = b;
        addBusInterceptors();
        FactoryBeanListenerManager fblm = bus.getExtension(FactoryBeanListenerManager.class);
        if (fblm != null) {
            fblm.addListener(new PolicyAnnotationListener());
        }
    }

    public Bus getBus() {
        return bus;
    }

    public void setPolicyProviders(Collection<PolicyProvider> p) {
        policyProviders = new CopyOnWriteArrayList<PolicyProvider>(p);
    }
   
    public synchronized Collection<PolicyProvider> getPolicyProviders() {
        if (policyProviders == null) {
            policyProviders = new CopyOnWriteArrayList<PolicyProvider>();
            if (bus != null) {
                ConfiguredBeanLocator loc = bus.getExtension(ConfiguredBeanLocator.class);
                if (loc != null) {
                    loc.getBeansOfType(PolicyProvider.class);
                }
            }
        }
        return policyProviders;
    }

    public void setRegistry(PolicyRegistry r) {
        registry = r;
    }

    public PolicyRegistry getRegistry() {
        return registry;
    }

    public synchronized void setEnabled(boolean e) {
        enabled = e;
        if (enabled && !addedBusInterceptors) {
            addBusInterceptors();
        } else if (!enabled && addedBusInterceptors) {
            removeBusInterceptors();
        }
    }

    public synchronized AlternativeSelector getAlternativeSelector() {  
        if (alternativeSelector == null && enabled) {
            alternativeSelector = new MinimalAlternativeSelector();
        }
        return alternativeSelector;
    }

    public void setAlternativeSelector(AlternativeSelector as) {
        alternativeSelector = as;
    }

    public boolean isIgnoreUnknownAssertions() {
        return ignoreUnknownAssertions == null ? true : ignoreUnknownAssertions;
    }

    public void setIgnoreUnknownAssertions(boolean ignore) {
        ignoreUnknownAssertions = ignore;
    }

    // BusExtension interface


    public Class<?> getRegistrationType() {
        return PolicyEngine.class;
    }

    // PolicyEngine interface

    public EffectivePolicy getEffectiveClientRequestPolicy(EndpointInfo ei, BindingOperationInfo boi, 
                                                           Conduit c) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)boi.getProperty(POLICY_INFO_REQUEST_CLIENT);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            Assertor assertor = null;
            if (c instanceof Assertor) {
                assertor = (Assertor)c;
            }
            epi.initialise(ei, boi, this, assertor, true, true);
            boi.setProperty(POLICY_INFO_REQUEST_CLIENT, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveClientRequestPolicy(EndpointInfo ei, BindingOperationInfo boi, 
                                                EffectivePolicy ep) {
        boi.setProperty(POLICY_INFO_REQUEST_CLIENT, ep);
    }

    public EffectivePolicy getEffectiveServerResponsePolicy(EndpointInfo ei,
                                                            BindingOperationInfo boi,
                                                            Destination d) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)boi.getProperty(POLICY_INFO_RESPONSE_SERVER);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            Assertor assertor = null;
            if (d instanceof Assertor) {
                assertor = (Assertor)d;
            }
            epi.initialise(ei, boi, this, assertor, false, false);
            boi.setProperty(POLICY_INFO_RESPONSE_SERVER, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveServerResponsePolicy(EndpointInfo ei, BindingOperationInfo boi, 
                                                 EffectivePolicy ep) {
        boi.setProperty(POLICY_INFO_RESPONSE_SERVER, ep);
    }
  
    public EffectivePolicy getEffectiveServerFaultPolicy(EndpointInfo ei, BindingFaultInfo bfi, 
                                                         Destination d) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)bfi.getProperty(POLICY_INFO_FAULT_SERVER);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            Assertor assertor = null;
            if (d instanceof Assertor) {
                assertor = (Assertor)d;
            }
            epi.initialise(ei, bfi, this, assertor);
            bfi.setProperty(POLICY_INFO_FAULT_SERVER, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveServerFaultPolicy(EndpointInfo ei, BindingFaultInfo bfi, EffectivePolicy ep) {
        bfi.setProperty(POLICY_INFO_FAULT_SERVER, ep);
    }

    public EndpointPolicy getClientEndpointPolicy(EndpointInfo ei, Conduit conduit) {
        Assertor assertor = conduit instanceof Assertor ? (Assertor)conduit : null;
        return getEndpointPolicy(ei, true, assertor);
    }
   
    public EndpointPolicy getServerEndpointPolicy(EndpointInfo ei, Destination destination) {
    
        Assertor assertor = destination instanceof Assertor ? (Assertor)destination : null;
        return getEndpointPolicy(ei, false, assertor);
    }

    private EndpointPolicy getEndpointPolicy(
        EndpointInfo ei, 
        boolean isRequestor,
        Assertor assertor) {
        EndpointPolicy ep = (EndpointPolicy)ei.getProperty(isRequestor ? POLICY_INFO_ENDPOINT_CLIENT 
                            : POLICY_INFO_ENDPOINT_SERVER);
        if (null != ep) {
            return ep; 
        }
        return createEndpointPolicyInfo(ei, isRequestor, assertor);
    }

    public void setClientEndpointPolicy(EndpointInfo ei, EndpointPolicy ep) {
        ei.setProperty(POLICY_INFO_ENDPOINT_CLIENT, ep);
    }

    public void setServerEndpointPolicy(EndpointInfo ei, EndpointPolicy ep) {
        ei.setProperty(POLICY_INFO_ENDPOINT_SERVER, ep);
    }

    public EffectivePolicy getEffectiveServerRequestPolicy(EndpointInfo ei, 
                                                           BindingOperationInfo boi) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)boi.getProperty(POLICY_INFO_REQUEST_SERVER);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            epi.initialise(ei, boi, this, false, true);
            boi.setProperty(POLICY_INFO_REQUEST_SERVER, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveServerRequestPolicy(EndpointInfo ei, BindingOperationInfo boi, 
                                                EffectivePolicy ep) {
        boi.setProperty(POLICY_INFO_REQUEST_SERVER, ep);        
    }

    public EffectivePolicy getEffectiveClientResponsePolicy(EndpointInfo ei, BindingOperationInfo boi) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)boi.getProperty(POLICY_INFO_RESPONSE_CLIENT);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            epi.initialise(ei, boi, this, true, false);        
            boi.setProperty(POLICY_INFO_RESPONSE_CLIENT, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveClientResponsePolicy(EndpointInfo ei, BindingOperationInfo boi, 
                                                 EffectivePolicy ep) {
        boi.setProperty(POLICY_INFO_RESPONSE_CLIENT, ep);
    }

    public EffectivePolicy getEffectiveClientFaultPolicy(EndpointInfo ei, BindingFaultInfo bfi) {
        EffectivePolicy effectivePolicy = (EffectivePolicy)bfi.getProperty(POLICY_INFO_FAULT_CLIENT);
        if (null == effectivePolicy) {
            EffectivePolicyImpl epi = createOutPolicyInfo();
            epi.initialisePolicy(ei, bfi, this);
            bfi.setProperty(POLICY_INFO_FAULT_CLIENT, epi);
            effectivePolicy = epi;
        }
        return effectivePolicy;
    }

    public void setEffectiveClientFaultPolicy(EndpointInfo ei, BindingFaultInfo bfi, EffectivePolicy ep) {
        bfi.setProperty(POLICY_INFO_FAULT_CLIENT, ep);
    }

    // implementation

    protected final void init() {
        registry = new PolicyRegistryImpl();
    }
    


    public synchronized void removeBusInterceptors() {
        bus.getInInterceptors().remove(PolicyInInterceptor.INSTANCE);
        bus.getOutInterceptors().remove(PolicyOutInterceptor.INSTANCE);
        bus.getInFaultInterceptors().remove(ClientPolicyInFaultInterceptor.INSTANCE);
        bus.getOutFaultInterceptors().remove(ServerPolicyOutFaultInterceptor.INSTANCE);
        bus.getInFaultInterceptors().add(PolicyVerificationInFaultInterceptor.INSTANCE);
        addedBusInterceptors = false;
    }

    public final synchronized void addBusInterceptors() {
        if (null == bus || !enabled) {
            return;
        }
    
        if (ignoreUnknownAssertions != null) {
            AssertionBuilderRegistry abr = bus.getExtension(AssertionBuilderRegistry.class);
            if (null != abr) {
                abr.setIgnoreUnknownAssertions(ignoreUnknownAssertions);
            }
        }

        bus.getInInterceptors().add(PolicyInInterceptor.INSTANCE);
        bus.getOutInterceptors().add(PolicyOutInterceptor.INSTANCE);
        bus.getInFaultInterceptors().add(ClientPolicyInFaultInterceptor.INSTANCE);
        bus.getOutFaultInterceptors().add(ServerPolicyOutFaultInterceptor.INSTANCE);
        bus.getInFaultInterceptors().add(PolicyVerificationInFaultInterceptor.INSTANCE);
    
        addedBusInterceptors = true;
    }  

    Policy getAggregatedServicePolicy(ServiceInfo si) {
        if (si == null) {
            return new Policy();
        }
        Policy aggregated = null;
        for (PolicyProvider pp : getPolicyProviders()) {
            Policy p = pp.getEffectivePolicy(si);
            if (null == aggregated) {
                aggregated = p;
            } else if (p != null) {
                aggregated = aggregated.merge(p);
            }
        }
        return aggregated == null ? new Policy() : aggregated;
    }

    Policy getAggregatedEndpointPolicy(EndpointInfo ei) {
        Policy aggregated = null;
        for (PolicyProvider pp : getPolicyProviders()) {
            Policy p = pp.getEffectivePolicy(ei);
            if (null == aggregated) {
                aggregated = p;
            } else if (p != null) {
                aggregated = aggregated.merge(p);
            }
        }
        return aggregated == null ? new Policy() : aggregated;
    }

    Policy getAggregatedOperationPolicy(BindingOperationInfo boi) {
        Policy aggregated = null;
        for (PolicyProvider pp : getPolicyProviders()) {
            Policy p = pp.getEffectivePolicy(boi);
            if (null == aggregated) {
                aggregated = p;
            } else if (p != null) {
                aggregated = aggregated.merge(p);
            }
        }
        return aggregated == null ? new Policy() : aggregated;
    }

    Policy getAggregatedMessagePolicy(BindingMessageInfo bmi) {
        Policy aggregated = null;
        for (PolicyProvider pp : getPolicyProviders()) {
            Policy p = pp.getEffectivePolicy(bmi);
            if (null == aggregated) {
                aggregated = p;
            } else if (p != null) {
                aggregated = aggregated.merge(p);
            }
        }
        return aggregated == null ? new Policy() : aggregated;
    }

    Policy getAggregatedFaultPolicy(BindingFaultInfo bfi) {
        Policy aggregated = null;
        for (PolicyProvider pp : getPolicyProviders()) {
            Policy p = pp.getEffectivePolicy(bfi);
            if (null == aggregated) {
                aggregated = p;
            } else if (p != null) {
                aggregated = aggregated.merge(p);
            }
        }
        return aggregated == null ? new Policy() : aggregated;
    }

    /**
     * Return a collection of all assertions used in the given policy component,
     * optionally including optional assertions.
     * The policy need not be normalised, so any policy references will have to be resolved.
     * @param pc the policy component
     * @param includeOptional flag indicating if optional assertions should be included
     * @return the assertions
     */
    Collection<PolicyAssertion> getAssertions(PolicyComponent pc, boolean includeOptional) {
    
        Collection<PolicyAssertion> assertions = new ArrayList<PolicyAssertion>();
    
        if (Constants.TYPE_ASSERTION == pc.getType()) {
            PolicyAssertion a = (PolicyAssertion)pc;
            if (includeOptional || !a.isOptional()) {
                assertions.add(a);
            }
        } else {   
            addAssertions(pc, includeOptional, assertions);
        }
        return assertions;
    }
    Collection<PolicyAssertion> getAssertions(EffectivePolicy pc, boolean includeOptional) {
        if (pc == null || pc.getChosenAlternative() == null) {
            return null;
        }
        Collection<PolicyAssertion> assertions = new ArrayList<PolicyAssertion>();
        for (PolicyAssertion assertion : pc.getChosenAlternative()) {
            if (Constants.TYPE_ASSERTION == assertion.getType()) {
                PolicyAssertion a = assertion;
                if (includeOptional || !a.isOptional()) {
                    assertions.add(a);
                }
            } else {   
                addAssertions(assertion, includeOptional, assertions);
            }
        }
        return assertions;
    }

    void addAssertions(PolicyComponent pc, boolean includeOptional, 
                               Collection<PolicyAssertion> assertions) {
   
        if (Constants.TYPE_ASSERTION == pc.getType()) {
            PolicyAssertion a = (PolicyAssertion)pc;
            if (includeOptional || !a.isOptional()) {
                assertions.add((PolicyAssertion)pc);            
            }
            return;
        } 
    
        if (Constants.TYPE_POLICY_REF == pc.getType()) {
            PolicyReference pr = (PolicyReference)pc;
            pc = pr.normalize(registry, false);
        }

        PolicyOperator po = (PolicyOperator)pc;

        List<PolicyComponent> pcs = CastUtils.cast(po.getPolicyComponents(), PolicyComponent.class);
        for (PolicyComponent child : pcs) {
            addAssertions(child, includeOptional, assertions);
        }
    }

    /**
     * Return the vocabulary of a policy component, i.e. the set of QNames of
     * the assertions used in the componente, duplicates removed.
     * @param pc the policy component
     * @param includeOptional flag indicating if optional assertions should be included
     * @return the vocabulary
     */
    Set<QName> getVocabulary(PolicyComponent pc, boolean includeOptional) {
        Collection<PolicyAssertion> assertions = getAssertions(pc, includeOptional);
        Set<QName> vocabulary = new HashSet<QName>();
        for (PolicyAssertion a : assertions) {
            vocabulary.add(a.getName());
        }
        return vocabulary;
    } 

    EndpointPolicyImpl createEndpointPolicyInfo(EndpointInfo ei, 
                                                boolean isRequestor, 
                                                Assertor assertor) {
        EndpointPolicyImpl epi = new EndpointPolicyImpl(ei, this, isRequestor, assertor);
        epi.initialize();
        ei.setProperty(isRequestor ? POLICY_INFO_ENDPOINT_CLIENT : POLICY_INFO_ENDPOINT_SERVER, epi);
        return epi;
    }


    /**
     * Check if a given list of assertions can potentially be supported by
     * interceptors or by an already installed assertor (a conduit or transport
     * that implements the Assertor interface).
     * 
     * @param alternative the policy alternative
     * @param Assertor the assertor
     * @return true iff the alternative can be supported
     */
    public boolean supportsAlternative(Collection<PolicyAssertion> alternative, 
                                       Assertor assertor) {
        PolicyInterceptorProviderRegistry pipr = 
            bus.getExtension(PolicyInterceptorProviderRegistry.class);
        for (PolicyAssertion a : alternative) {
            if (!(a.isOptional() 
                || (null != pipr.get(a.getName())) 
                || (null != assertor && assertor.canAssert(a.getName())))) {
                
                LOG.fine("Alternative " + a.getName() + " is not supported");
                return false;
            }
        }
        return true;
    }


    // for test
    EffectivePolicyImpl createOutPolicyInfo() {
        return new EffectivePolicyImpl();
    }
    

}
