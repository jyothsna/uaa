/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.login.saml;

import org.cloudfoundry.identity.uaa.ExternalIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.manager.AuthEvent;
import org.cloudfoundry.identity.uaa.rest.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.bootstrap.ScimUserBootstrap;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.user.JdbcUaaUserDatabase;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.JdbcIdentityProviderProvisioning;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.NameID;
import org.opensaml.ws.wssecurity.impl.AttributedStringImpl;
import org.opensaml.xml.XMLObject;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.saml.SAMLAuthenticationToken;
import org.springframework.security.saml.SAMLConstants;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.security.saml.context.SAMLMessageContext;
import org.springframework.security.saml.log.SAMLLogger;
import org.springframework.security.saml.metadata.ExtendedMetadata;
import org.springframework.security.saml.websso.WebSSOProfileConsumer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LoginSamlAuthenticationProviderTests extends JdbcTestBase {

    public static final String SAML_USER = "saml.user";
    public static final String SAML_ADMIN = "saml.admin";
    public static final String SAML_TEST = "saml.test";
    public static final String SAML_NOT_MAPPED = "saml.unmapped";
    public static final String UAA_SAML_USER = "uaa.saml.user";
    public static final String UAA_SAML_ADMIN = "uaa.saml.admin";
    public static final String UAA_SAML_TEST = "uaa.saml.test";
    IdentityProviderProvisioning providerProvisioning;
    ApplicationEventPublisher publisher;
    JdbcUaaUserDatabase userDatabase;
    LoginSamlAuthenticationProvider authprovider;
    WebSSOProfileConsumer consumer;
    SAMLCredential credential;
    SAMLLogger samlLogger = mock(SAMLLogger.class);
    SamlIdentityProviderDefinition providerDefinition = new SamlIdentityProviderDefinition();
    private IdentityProvider provider;

    public List<Attribute> getAttributes(Map<String,Object> values) {
        List<Attribute> result = new LinkedList<>();
        for (Map.Entry<String,Object> entry : values.entrySet()) {
            result.addAll(getAttributes(entry.getKey(), entry.getValue()));
        }
        return result;
    }
    public List<Attribute> getAttributes(final String name, Object value) {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getName()).thenReturn(name);
        when(attribute.getFriendlyName()).thenReturn(name);

        List<XMLObject> xmlObjects = new LinkedList<>();
        if (value instanceof List) {
            for (String s : (List<String>)value) {
                AttributedStringImpl impl = new AttributedStringImpl("", "", "");
                impl.setValue(s);
                xmlObjects.add(impl);
            }
        } else {
            AttributedStringImpl impl = new AttributedStringImpl("", "", "");
            impl.setValue((String)value);
            xmlObjects.add(impl);
        }
        when(attribute.getAttributeValues()).thenReturn(xmlObjects);
        return Arrays.asList(attribute);
    }

    @Before
    public void configureProvider() throws Exception {
        ScimUserProvisioning userProvisioning = new JdbcScimUserProvisioning(jdbcTemplate, new JdbcPagingListFactory(jdbcTemplate, limitSqlAdapter));
        ScimGroupProvisioning groupProvisioning = new JdbcScimGroupProvisioning(jdbcTemplate, new JdbcPagingListFactory(jdbcTemplate, limitSqlAdapter));

        ScimGroup uaaSamlUser = groupProvisioning.create(new ScimGroup(null,UAA_SAML_USER,IdentityZone.getUaa().getId()));
        ScimGroup uaaSamlAdmin = groupProvisioning.create(new ScimGroup(null,UAA_SAML_ADMIN,IdentityZone.getUaa().getId()));
        ScimGroup uaaSamlTest = groupProvisioning.create(new ScimGroup(null,UAA_SAML_TEST,IdentityZone.getUaa().getId()));

        JdbcScimGroupMembershipManager membershipManager = new JdbcScimGroupMembershipManager(jdbcTemplate, new JdbcPagingListFactory(jdbcTemplate, limitSqlAdapter));
        membershipManager.setScimGroupProvisioning(groupProvisioning);
        membershipManager.setScimUserProvisioning(userProvisioning);
        ScimUserBootstrap bootstrap = new ScimUserBootstrap(userProvisioning, groupProvisioning, membershipManager, Collections.EMPTY_LIST);

        JdbcScimGroupExternalMembershipManager externalManager = new JdbcScimGroupExternalMembershipManager(jdbcTemplate, new JdbcPagingListFactory(jdbcTemplate, limitSqlAdapter));
        externalManager.setScimGroupProvisioning(groupProvisioning);
        externalManager.mapExternalGroup(uaaSamlUser.getId(), SAML_USER, Origin.SAML);
        externalManager.mapExternalGroup(uaaSamlAdmin.getId(), SAML_ADMIN, Origin.SAML);
        externalManager.mapExternalGroup(uaaSamlTest.getId(), SAML_TEST, Origin.SAML);

        consumer = mock(WebSSOProfileConsumer.class);
        credential = getUserCredential("marissa-saml", "Marissa", "Bloggs", "marissa.bloggs@test.com", "1234567890");
        when(consumer.processAuthenticationResponse(anyObject())).thenReturn(credential);

        userDatabase = new JdbcUaaUserDatabase(jdbcTemplate);
        userDatabase.setUserAuthoritiesQuery("select g.displayName from groups g, group_membership m where g.id = m.group_id and m.member_id = ?");
        userDatabase.setDefaultAuthorities(new HashSet<>(Arrays.asList(UaaAuthority.UAA_USER.getAuthority())));
        providerProvisioning = new JdbcIdentityProviderProvisioning(jdbcTemplate);
        publisher = new CreateUserPublisher(bootstrap);
        authprovider = new LoginSamlAuthenticationProvider();

        authprovider.setUserDatabase(userDatabase);
        authprovider.setIdentityProviderProvisioning(providerProvisioning);
        authprovider.setApplicationEventPublisher(publisher);
        authprovider.setConsumer(consumer);
        authprovider.setSamlLogger(samlLogger);
        authprovider.setExternalMembershipManager(externalManager);

        provider = new IdentityProvider();
        provider.setIdentityZoneId(IdentityZone.getUaa().getId());
        provider.setOriginKey(Origin.SAML);
        provider.setName("saml-test");
        provider.setActive(true);
        provider.setType(Origin.SAML);
        providerDefinition.setMetaDataLocation(String.format(IDP_META_DATA, Origin.SAML));
        providerDefinition.setIdpEntityAlias(Origin.SAML);
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        provider = providerProvisioning.create(provider);
    }

    private SAMLCredential getUserCredential(String username, String firstName, String lastName, String emailAddress, String phoneNumber) {
        NameID usernameID = mock(NameID.class);
        when(usernameID.getValue()).thenReturn(username);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("firstName", firstName);
        attributes.put("lastName", lastName);
        attributes.put("emailAddress", emailAddress);
        attributes.put("phone", phoneNumber);
        attributes.put("groups", Arrays.asList(SAML_USER, SAML_ADMIN, SAML_NOT_MAPPED));
        attributes.put("2ndgroups", Arrays.asList(SAML_TEST));
        return new SAMLCredential(
            usernameID,
            mock(Assertion.class),
            "remoteEntityID",
            getAttributes(attributes),
            "localEntityID");
    }

    @Test
    public void testAuthenticateSimple() {
        authprovider.authenticate(mockSamlAuthentication(Origin.SAML));
    }

    @Test
    public void test_multiple_group_attributes() throws Exception {
        providerDefinition.addAttributeMapping(ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME, Arrays.asList("2ndgroups","groups"));
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);
        UaaAuthentication authentication = getAuthentication();
        assertEquals("Four authorities should have been granted!", 4, authentication.getAuthorities().size());
        assertThat(authentication.getAuthorities(),
                   Matchers.containsInAnyOrder(
                       new SimpleGrantedAuthority(UAA_SAML_ADMIN),
                       new SimpleGrantedAuthority(UAA_SAML_USER),
                       new SimpleGrantedAuthority(UAA_SAML_TEST),
                       new SimpleGrantedAuthority(UaaAuthority.UAA_USER.getAuthority())
                   )
        );
    }

    @Test
    public void test_group_mapping() throws Exception {
        providerDefinition.addAttributeMapping(ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME, "groups");
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);
        UaaAuthentication authentication = getAuthentication();
        assertEquals("Three authorities should have been granted!", 3, authentication.getAuthorities().size());
        assertThat(authentication.getAuthorities(),
                   Matchers.containsInAnyOrder(
                       new SimpleGrantedAuthority(UAA_SAML_ADMIN),
                       new SimpleGrantedAuthority(UAA_SAML_USER),
                       new SimpleGrantedAuthority(UaaAuthority.UAA_USER.getAuthority())
                   )
        );
    }

    @Test
    public void externalGroup_NotMapped_ToScope() throws Exception {
        providerDefinition.addAttributeMapping(ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME, "groups");
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);
        UaaAuthentication authentication = getAuthentication();
        assertEquals("Three authorities should have been granted!", 3, authentication.getAuthorities().size());
        assertThat(authentication.getAuthorities(),
                Matchers.containsInAnyOrder(
                        new SimpleGrantedAuthority(UAA_SAML_ADMIN),
                        new SimpleGrantedAuthority(UAA_SAML_USER),
                        new SimpleGrantedAuthority(UaaAuthority.UAA_USER.getAuthority())
                )
        );
    }

    @Test
    public void test_group_attribute_not_set() throws Exception {
        UaaAuthentication uaaAuthentication = getAuthentication();
        assertEquals("Only uaa.user should have been granted", 1, uaaAuthentication.getAuthorities().size());
        assertEquals(UaaAuthority.UAA_USER.getAuthority(), uaaAuthentication.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    public void add_external_groups_to_authentication_without_whitelist() throws Exception {
        providerDefinition.addAttributeMapping(ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME, "groups");
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);

        UaaAuthentication authentication = getAuthentication();
        assertThat(authentication.getExternalGroups(), Matchers.containsInAnyOrder(SAML_ADMIN, SAML_USER, SAML_NOT_MAPPED));
    }

    @Test
    public void add_external_groups_to_authentication_with_whitelist() throws Exception {
        providerDefinition.addAttributeMapping(ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME, "groups");
        providerDefinition.addWhiteListedGroup(SAML_ADMIN);
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);

        UaaAuthentication authentication = getAuthentication();
        assertEquals(Collections.singleton(SAML_ADMIN), authentication.getExternalGroups());
    }

    @Test
    public void update_existingUser_if_attributes_different() throws Exception {
        getAuthentication();

        Map<String,Object> attributeMappings = new HashMap<>();
        attributeMappings.put("given_name", "firstName");
        attributeMappings.put("email", "emailAddress");
        providerDefinition.setAttributeMappings(attributeMappings);
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);

        SAMLCredential credential = getUserCredential("marissa-saml", "Marissa-changed", null, "marissa.bloggs@change.org", null);
        when(consumer.processAuthenticationResponse(anyObject())).thenReturn(credential);
        getAuthentication();

        UaaUser user = userDatabase.retrieveUserByName("marissa-saml", Origin.SAML);
        assertEquals("Marissa-changed", user.getGivenName());
        assertEquals("marissa.bloggs@change.org", user.getEmail());
    }

    @Test
    public void dont_update_existingUser_if_attributes_areTheSame() throws Exception {
        getAuthentication();
        UaaUser user = userDatabase.retrieveUserByName("marissa-saml", Origin.SAML);

        getAuthentication();
        UaaUser existingUser = userDatabase.retrieveUserByName("marissa-saml", Origin.SAML);

        assertEquals(existingUser.getModified(), user.getModified());
    }

    @Test
    public void shadowAccount_createdWith_MappedUserAttributes() throws Exception {
        Map<String,Object> attributeMappings = new HashMap<>();
        attributeMappings.put("given_name", "firstName");
        attributeMappings.put("family_name", "lastName");
        attributeMappings.put("email", "emailAddress");
        attributeMappings.put("phone_number", "phone");
        providerDefinition.setAttributeMappings(attributeMappings);
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);

        getAuthentication();
        UaaUser user = userDatabase.retrieveUserByName("marissa-saml", Origin.SAML);
        assertEquals("Marissa", user.getGivenName());
        assertEquals("Bloggs", user.getFamilyName());
        assertEquals("marissa.bloggs@test.com", user.getEmail());
        assertEquals("1234567890", user.getPhoneNumber());
    }

    @Test
    public void shadowUser_GetsCreatedWithDefaultValues_IfAttributeNotMapped() throws Exception {
        Map<String,Object> attributeMappings = new HashMap<>();
        attributeMappings.put("surname", "lastName");
        attributeMappings.put("email", "emailAddress");
        providerDefinition.setAttributeMappings(attributeMappings);
        provider.setConfig(JsonUtils.writeValueAsString(providerDefinition));
        providerProvisioning.update(provider);

        getAuthentication();
        UaaUser user = userDatabase.retrieveUserByName("marissa-saml", Origin.SAML);
        assertEquals("marissa.bloggs", user.getGivenName());
        assertEquals("test.com", user.getFamilyName());
        assertEquals("marissa.bloggs@test.com", user.getEmail());
    }

    protected UaaAuthentication getAuthentication() {
        Authentication authentication = authprovider.authenticate(mockSamlAuthentication(Origin.SAML));
        assertNotNull("Authentication should exist", authentication);
        assertTrue("Authentication should be UaaAuthentication", authentication instanceof UaaAuthentication);
        return (UaaAuthentication)authentication;
    }


    protected SAMLAuthenticationToken mockSamlAuthentication(String originKey) {
        ExtendedMetadata metadata = mock(ExtendedMetadata.class);
        when(metadata.getAlias()).thenReturn(originKey);
        SAMLMessageContext contxt = mock(SAMLMessageContext.class);
        when(contxt.getPeerExtendedMetadata()).thenReturn(metadata);
        when(contxt.getCommunicationProfileId()).thenReturn(SAMLConstants.SAML2_WEBSSO_PROFILE_URI);
        return new SAMLAuthenticationToken(contxt);
    }

    public static class CreateUserPublisher implements ApplicationEventPublisher {

        final ScimUserBootstrap bootstrap;

        public CreateUserPublisher(ScimUserBootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }


        @Override
        public void publishEvent(ApplicationEvent event) {
            if (event instanceof AuthEvent) {
                bootstrap.onApplicationEvent((AuthEvent)event);
            }
        }
    }


    public static final String IDP_META_DATA =
        "<?xml version=\"1.0\"?>\n" +
            "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" entityID=\"%s\" ID=\"pfx06ad4153-c17c-d286-194c-dec30bb92796\"><ds:Signature>\n" +
            "  <ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/>\n" +
            "    <ds:SignatureMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#rsa-sha1\"/>\n" +
            "  <ds:Reference URI=\"#pfx06ad4153-c17c-d286-194c-dec30bb92796\"><ds:Transforms><ds:Transform Algorithm=\"http://www.w3.org/2000/09/xmldsig#enveloped-signature\"/><ds:Transform Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/></ds:Transforms><ds:DigestMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#sha1\"/><ds:DigestValue>begl1WVCsXSn7iHixtWPP8d/X+k=</ds:DigestValue></ds:Reference></ds:SignedInfo><ds:SignatureValue>BmbKqA3A0oSLcn5jImz/l5WbpVXj+8JIpT/ENWjOjSd/gcAsZm1QvYg+RxYPBk+iV2bBxD+/yAE/w0wibsHrl0u9eDhoMRUJBUSmeyuN1lYzBuoVa08PdAGtb5cGm4DMQT5Rzakb1P0hhEPPEDDHgTTxop89LUu6xx97t2Q03Khy8mXEmBmNt2NlFxJPNt0FwHqLKOHRKBOE/+BpswlBocjOQKFsI9tG3TyjFC68mM2jo0fpUQCgj5ZfhzolvS7z7c6V201d9Tqig0/mMFFJLTN8WuZPavw22AJlMjsDY9my+4R9HKhK5U53DhcTeECs9fb4gd7p5BJy4vVp7tqqOg==</ds:SignatureValue>\n" +
            "<ds:KeyInfo><ds:X509Data><ds:X509Certificate>MIIEEzCCAvugAwIBAgIJAIc1qzLrv+5nMA0GCSqGSIb3DQEBCwUAMIGfMQswCQYDVQQGEwJVUzELMAkGA1UECAwCQ08xFDASBgNVBAcMC0Nhc3RsZSBSb2NrMRwwGgYDVQQKDBNTYW1sIFRlc3RpbmcgU2VydmVyMQswCQYDVQQLDAJJVDEgMB4GA1UEAwwXc2ltcGxlc2FtbHBocC5jZmFwcHMuaW8xIDAeBgkqhkiG9w0BCQEWEWZoYW5pa0BwaXZvdGFsLmlvMB4XDTE1MDIyMzIyNDUwM1oXDTI1MDIyMjIyNDUwM1owgZ8xCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDTzEUMBIGA1UEBwwLQ2FzdGxlIFJvY2sxHDAaBgNVBAoME1NhbWwgVGVzdGluZyBTZXJ2ZXIxCzAJBgNVBAsMAklUMSAwHgYDVQQDDBdzaW1wbGVzYW1scGhwLmNmYXBwcy5pbzEgMB4GCSqGSIb3DQEJARYRZmhhbmlrQHBpdm90YWwuaW8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC4cn62E1xLqpN34PmbrKBbkOXFjzWgJ9b+pXuaRft6A339uuIQeoeH5qeSKRVTl32L0gdz2ZivLwZXW+cqvftVW1tvEHvzJFyxeTW3fCUeCQsebLnA2qRa07RkxTo6Nf244mWWRDodcoHEfDUSbxfTZ6IExSojSIU2RnD6WllYWFdD1GFpBJOmQB8rAc8wJIBdHFdQnX8Ttl7hZ6rtgqEYMzYVMuJ2F2r1HSU1zSAvwpdYP6rRGFRJEfdA9mm3WKfNLSc5cljz0X/TXy0vVlAV95l9qcfFzPmrkNIst9FZSwpvB49LyAVke04FQPPwLgVH4gphiJH3jvZ7I+J5lS8VAgMBAAGjUDBOMB0GA1UdDgQWBBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAfBgNVHSMEGDAWgBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAvMS4EQeP/ipV4jOG5lO6/tYCb/iJeAduOnRhkJk0DbX329lDLZhTTL/x/w/9muCVcvLrzEp6PN+VWfw5E5FWtZN0yhGtP9R+vZnrV+oc2zGD+no1/ySFOe3EiJCO5dehxKjYEmBRv5sU/LZFKZpozKN/BMEa6CqLuxbzb7ykxVr7EVFXwltPxzE9TmL9OACNNyF5eJHWMRMllarUvkcXlh4pux4ks9e6zV9DQBy2zds9f1I3qxg0eX6JnGrXi/ZiCT+lJgVe3ZFXiejiLAiKB04sXW3ti0LW3lx13Y1YlQ4/tlpgTgfIJxKV6nyPiLoK0nywbMd+vpAirDt2Oc+hk</ds:X509Certificate></ds:X509Data></ds:KeyInfo></ds:Signature>\n" +
            "  <md:IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">\n" +
            "    <md:KeyDescriptor use=\"signing\">\n" +
            "      <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n" +
            "        <ds:X509Data>\n" +
            "          <ds:X509Certificate>MIIEEzCCAvugAwIBAgIJAIc1qzLrv+5nMA0GCSqGSIb3DQEBCwUAMIGfMQswCQYDVQQGEwJVUzELMAkGA1UECAwCQ08xFDASBgNVBAcMC0Nhc3RsZSBSb2NrMRwwGgYDVQQKDBNTYW1sIFRlc3RpbmcgU2VydmVyMQswCQYDVQQLDAJJVDEgMB4GA1UEAwwXc2ltcGxlc2FtbHBocC5jZmFwcHMuaW8xIDAeBgkqhkiG9w0BCQEWEWZoYW5pa0BwaXZvdGFsLmlvMB4XDTE1MDIyMzIyNDUwM1oXDTI1MDIyMjIyNDUwM1owgZ8xCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDTzEUMBIGA1UEBwwLQ2FzdGxlIFJvY2sxHDAaBgNVBAoME1NhbWwgVGVzdGluZyBTZXJ2ZXIxCzAJBgNVBAsMAklUMSAwHgYDVQQDDBdzaW1wbGVzYW1scGhwLmNmYXBwcy5pbzEgMB4GCSqGSIb3DQEJARYRZmhhbmlrQHBpdm90YWwuaW8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC4cn62E1xLqpN34PmbrKBbkOXFjzWgJ9b+pXuaRft6A339uuIQeoeH5qeSKRVTl32L0gdz2ZivLwZXW+cqvftVW1tvEHvzJFyxeTW3fCUeCQsebLnA2qRa07RkxTo6Nf244mWWRDodcoHEfDUSbxfTZ6IExSojSIU2RnD6WllYWFdD1GFpBJOmQB8rAc8wJIBdHFdQnX8Ttl7hZ6rtgqEYMzYVMuJ2F2r1HSU1zSAvwpdYP6rRGFRJEfdA9mm3WKfNLSc5cljz0X/TXy0vVlAV95l9qcfFzPmrkNIst9FZSwpvB49LyAVke04FQPPwLgVH4gphiJH3jvZ7I+J5lS8VAgMBAAGjUDBOMB0GA1UdDgQWBBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAfBgNVHSMEGDAWgBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAvMS4EQeP/ipV4jOG5lO6/tYCb/iJeAduOnRhkJk0DbX329lDLZhTTL/x/w/9muCVcvLrzEp6PN+VWfw5E5FWtZN0yhGtP9R+vZnrV+oc2zGD+no1/ySFOe3EiJCO5dehxKjYEmBRv5sU/LZFKZpozKN/BMEa6CqLuxbzb7ykxVr7EVFXwltPxzE9TmL9OACNNyF5eJHWMRMllarUvkcXlh4pux4ks9e6zV9DQBy2zds9f1I3qxg0eX6JnGrXi/ZiCT+lJgVe3ZFXiejiLAiKB04sXW3ti0LW3lx13Y1YlQ4/tlpgTgfIJxKV6nyPiLoK0nywbMd+vpAirDt2Oc+hk</ds:X509Certificate>\n" +
            "        </ds:X509Data>\n" +
            "      </ds:KeyInfo>\n" +
            "    </md:KeyDescriptor>\n" +
            "    <md:KeyDescriptor use=\"encryption\">\n" +
            "      <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n" +
            "        <ds:X509Data>\n" +
            "          <ds:X509Certificate>MIIEEzCCAvugAwIBAgIJAIc1qzLrv+5nMA0GCSqGSIb3DQEBCwUAMIGfMQswCQYDVQQGEwJVUzELMAkGA1UECAwCQ08xFDASBgNVBAcMC0Nhc3RsZSBSb2NrMRwwGgYDVQQKDBNTYW1sIFRlc3RpbmcgU2VydmVyMQswCQYDVQQLDAJJVDEgMB4GA1UEAwwXc2ltcGxlc2FtbHBocC5jZmFwcHMuaW8xIDAeBgkqhkiG9w0BCQEWEWZoYW5pa0BwaXZvdGFsLmlvMB4XDTE1MDIyMzIyNDUwM1oXDTI1MDIyMjIyNDUwM1owgZ8xCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDTzEUMBIGA1UEBwwLQ2FzdGxlIFJvY2sxHDAaBgNVBAoME1NhbWwgVGVzdGluZyBTZXJ2ZXIxCzAJBgNVBAsMAklUMSAwHgYDVQQDDBdzaW1wbGVzYW1scGhwLmNmYXBwcy5pbzEgMB4GCSqGSIb3DQEJARYRZmhhbmlrQHBpdm90YWwuaW8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC4cn62E1xLqpN34PmbrKBbkOXFjzWgJ9b+pXuaRft6A339uuIQeoeH5qeSKRVTl32L0gdz2ZivLwZXW+cqvftVW1tvEHvzJFyxeTW3fCUeCQsebLnA2qRa07RkxTo6Nf244mWWRDodcoHEfDUSbxfTZ6IExSojSIU2RnD6WllYWFdD1GFpBJOmQB8rAc8wJIBdHFdQnX8Ttl7hZ6rtgqEYMzYVMuJ2F2r1HSU1zSAvwpdYP6rRGFRJEfdA9mm3WKfNLSc5cljz0X/TXy0vVlAV95l9qcfFzPmrkNIst9FZSwpvB49LyAVke04FQPPwLgVH4gphiJH3jvZ7I+J5lS8VAgMBAAGjUDBOMB0GA1UdDgQWBBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAfBgNVHSMEGDAWgBTTyP6Cc5HlBJ5+ucVCwGc5ogKNGzAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAvMS4EQeP/ipV4jOG5lO6/tYCb/iJeAduOnRhkJk0DbX329lDLZhTTL/x/w/9muCVcvLrzEp6PN+VWfw5E5FWtZN0yhGtP9R+vZnrV+oc2zGD+no1/ySFOe3EiJCO5dehxKjYEmBRv5sU/LZFKZpozKN/BMEa6CqLuxbzb7ykxVr7EVFXwltPxzE9TmL9OACNNyF5eJHWMRMllarUvkcXlh4pux4ks9e6zV9DQBy2zds9f1I3qxg0eX6JnGrXi/ZiCT+lJgVe3ZFXiejiLAiKB04sXW3ti0LW3lx13Y1YlQ4/tlpgTgfIJxKV6nyPiLoK0nywbMd+vpAirDt2Oc+hk</ds:X509Certificate>\n" +
            "        </ds:X509Data>\n" +
            "      </ds:KeyInfo>\n" +
            "    </md:KeyDescriptor>\n" +
            "    <md:SingleLogoutService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"http://simplesamlphp.cfapps.io/saml2/idp/SingleLogoutService.php\"/>\n" +
            "    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:transient</md:NameIDFormat>\n" +
            "    <md:SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"http://simplesamlphp.cfapps.io/saml2/idp/SSOService.php\"/>\n" +
            "  </md:IDPSSODescriptor>\n" +
            "  <md:ContactPerson contactType=\"technical\">\n" +
            "    <md:GivenName>Filip</md:GivenName>\n" +
            "    <md:SurName>Hanik</md:SurName>\n" +
            "    <md:EmailAddress>fhanik@pivotal.io</md:EmailAddress>\n" +
            "  </md:ContactPerson>\n" +
            "</md:EntityDescriptor>";
}

