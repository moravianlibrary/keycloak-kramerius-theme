/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eosc.kc.rest;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.forms.login.freemarker.LoginFormsUtil;
import org.keycloak.forms.login.freemarker.model.IdentityProviderBean;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IdpResourceProvider implements RealmResourceProvider {

    private KeycloakSession session;

    public IdpResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
        public void close() {
    }

    /**
     * This should be used from login pages to show all available identity providers of the realm for logging in.
     * It has to be a public endpoint.
     */
    @GET
    @Path("{realm}/identity-providers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IdentityProviderBean.IdentityProvider> getIdentityProviders(
            @PathParam("realm") String realmName,
            @QueryParam("keyword") @DefaultValue("") String keyword,
            @QueryParam("first") @DefaultValue("0") Integer firstResult,
            @QueryParam("max") @DefaultValue("2147483647") Integer maxResults,
            @QueryParam("client_id") @DefaultValue("") String clientId,
            @QueryParam("tab_id") @DefaultValue("") String tabId,
            @QueryParam("session_code") @DefaultValue("") String sessionCode
    ) {
        if(firstResult < 0 || maxResults < 0)
            throw new BadRequestException("Should specify params firstResult and maxResults to be >= 0");
        RealmModel realm = init(realmName);
        final String lowercaseKeyword = keyword.toLowerCase();
        List<IdentityProviderModel> identityProviders = realm.getIdentityProvidersStream()
                .filter(idp -> {
                    String name = idp.getDisplayName() == null ? "" : idp.getDisplayName();
                    return name.toLowerCase().contains(lowercaseKeyword) || idp.getAlias().toLowerCase().contains(lowercaseKeyword);
                })
                .skip(firstResult)
                .limit(maxResults)
                .collect(Collectors.toList());

        //this translates to http 204 code (instead of an empty list's 200). Is used to specify that its a end-of-stream.
        if(identityProviders.isEmpty())
            return null;

        AuthenticationSessionManager authSessionManager = new AuthenticationSessionManager(session);
        AuthenticationSessionModel authSessionModel = authSessionManager.getCurrentAuthenticationSession(realm, realm.getClientByClientId(clientId), tabId);
        identityProviders = filterIdentityProviders(identityProviders.stream(), session, authSessionModel);

        //Expose through the Bean, because it makes some extra processing. URI is re-composed back in the UI, so we can ignore here
        //returns empty list if all idps are filtered out, and not null. This is important for the UI
        IdentityProviderBean idpBean = new IdentityProviderBean(realm, session, identityProviders, URI.create(""));
        return idpBean.getProviders()!=null ? idpBean.getProviders() : new ArrayList<>();
    }


    /**
     * This should be used from login pages to show any promoted identity providers of the realm for logging in with.
     * It has to be a public endpoint.
     */
    @GET
    @Path("{realm}/identity-providers-promoted")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IdentityProviderBean.IdentityProvider> getPromotedIdentityProviders(
            @PathParam("realm") String realmName
    ) {
        RealmModel realm = init(realmName);
        List<IdentityProviderModel> promotedProviders = new ArrayList<>();
        realm.getIdentityProvidersStream().forEach(idp -> {
            if(idp.getConfig()!=null && "true".equals(idp.getConfig().get("specialLoginbutton")))
                promotedProviders.add(idp);
        });

        //Expose through the Bean, because it makes some extra processing. URI is re-composed back in the UI, so we can ignore here
        IdentityProviderBean idpBean = new IdentityProviderBean(realm, session, promotedProviders, URI.create(""));
        return idpBean.getProviders()!=null ? idpBean.getProviders() : new ArrayList<>();

    }



    private RealmModel init(String realmName) {
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            throw new NotFoundException("Realm does not exist");
        }
        session.getContext().setRealm(realm);
        return realm;
    }


    /**
     * <b> This is actually the function LoginFormsUtil.filterIdentityProviders() </b>
     *
     * @param providers
     * @param session
     * @param authSession
     * @return
     */
    public static List<IdentityProviderModel> filterIdentityProviders(Stream<IdentityProviderModel> providers, KeycloakSession session, AuthenticationSessionModel authSession) {
        if (authSession != null) {
            SerializedBrokeredIdentityContext serializedCtx = SerializedBrokeredIdentityContext.readFromAuthenticationSession(authSession, "BROKERED_CONTEXT");
            if (serializedCtx != null) {
                IdentityProviderModel idp = serializedCtx.deserialize(session, authSession).getIdpConfig();
                return (List)providers.filter((p) -> {
                    return !Objects.equals(p.getAlias(), idp.getAlias());
                }).collect(Collectors.toList());
            }
        }

        return (List)providers.collect(Collectors.toList());
    }


}
