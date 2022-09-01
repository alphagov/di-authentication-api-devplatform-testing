package uk.gov.di.authentication.shared.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.util.ArrayList;
import java.util.List;

@DynamoDbBean
public class ClientRegistry {

    private String clientID;
    private String clientName;
    private String publicKey;
    private List<String> postLogoutRedirectUrls = new ArrayList<>();
    public String backChannelLogoutUri;
    private List<String> scopes = new ArrayList<>();
    private List<String> redirectUrls = new ArrayList<>();
    private List<String> contacts = new ArrayList<>();
    private String serviceType;
    private String sectorIdentifierUri;
    private String subjectType;
    private boolean cookieConsentShared = false;
    private boolean consentRequired = false;
    private boolean testClient = false;
    private List<String> testClientEmailAllowlist = new ArrayList<>();
    private List<String> claims = new ArrayList<>();
    private String clientType;
    private boolean identityVerificationSupported = false;

    public ClientRegistry() {}

    @DynamoDbPartitionKey
    @DynamoDbAttribute("ClientID")
    public String getClientID() {
        return clientID;
    }

    public void setClientID(String clientID) {
        this.clientID = clientID;
    }

    public ClientRegistry withClientID(String clientID) {
        this.clientID = clientID;
        return this;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"ClientNameIndex"})
    @DynamoDbAttribute("ClientName")
    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public ClientRegistry withClientName(String clientName) {
        this.clientName = clientName;
        return this;
    }

    @DynamoDbAttribute("PublicKey")
    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public ClientRegistry withPublicKey(String publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    @DynamoDbAttribute("Scopes")
    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public ClientRegistry withScopes(List<String> scopes) {
        this.scopes = scopes;
        return this;
    }

    @DynamoDbAttribute("RedirectUrls")
    public List<String> getRedirectUrls() {
        return redirectUrls;
    }

    public void setRedirectUrls(List<String> redirectUrls) {
        this.redirectUrls = redirectUrls;
    }

    public ClientRegistry withRedirectUrls(List<String> redirectUrls) {
        this.redirectUrls = redirectUrls;
        return this;
    }

    @DynamoDbAttribute("Contacts")
    public List<String> getContacts() {
        return contacts;
    }

    public void setContacts(List<String> contacts) {
        this.contacts = contacts;
    }

    public ClientRegistry withContacts(List<String> contacts) {
        this.contacts = contacts;
        return this;
    }

    @DynamoDbAttribute("PostLogoutRedirectUrls")
    public List<String> getPostLogoutRedirectUrls() {
        return postLogoutRedirectUrls;
    }

    public void setPostLogoutRedirectUrls(List<String> postLogoutRedirectUrls) {
        this.postLogoutRedirectUrls = postLogoutRedirectUrls;
    }

    public ClientRegistry withPostLogoutRedirectUrls(List<String> postLogoutRedirectUrls) {
        this.postLogoutRedirectUrls = postLogoutRedirectUrls;
        return this;
    }

    @DynamoDbAttribute("BackChannelLogoutUri")
    public String getBackChannelLogoutUri() {
        return backChannelLogoutUri;
    }

    public void setBackChannelLogoutUri(String backChannelLogoutUri) {
        this.backChannelLogoutUri = backChannelLogoutUri;
    }

    public ClientRegistry withBackChannelLogoutUri(String backChannelLogoutUri) {
        this.backChannelLogoutUri = backChannelLogoutUri;
        return this;
    }

    @DynamoDbAttribute("ServiceType")
    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public ClientRegistry withServiceType(String serviceType) {
        this.serviceType = serviceType;
        return this;
    }

    @DynamoDbAttribute("SectorIdentifierUri")
    public String getSectorIdentifierUri() {
        return sectorIdentifierUri;
    }

    public void setSectorIdentifierUri(String sectorIdentifierUri) {
        this.sectorIdentifierUri = sectorIdentifierUri;
    }

    public ClientRegistry withSectorIdentifierUri(String sectorIdentifierUri) {
        this.sectorIdentifierUri = sectorIdentifierUri;
        return this;
    }

    @DynamoDbAttribute("SubjectType")
    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public ClientRegistry withSubjectType(String subjectType) {
        this.subjectType = subjectType;
        return this;
    }

    @DynamoDbAttribute("CookieConsentShared")
    public boolean isCookieConsentShared() {
        return cookieConsentShared;
    }

    public void setCookieConsentShared(boolean cookieConsentShared) {
        this.cookieConsentShared = cookieConsentShared;
    }

    public ClientRegistry withCookieConsentShared(boolean cookieConsent) {
        this.cookieConsentShared = cookieConsent;
        return this;
    }

    @DynamoDbAttribute("TestClient")
    public boolean isTestClient() {
        return testClient;
    }

    public void setTestClient(boolean testClient) {
        this.testClient = testClient;
    }

    public ClientRegistry withTestClient(boolean testClient) {
        this.testClient = testClient;
        return this;
    }

    @DynamoDbAttribute("TestClientEmailAllowlist")
    public List<String> getTestClientEmailAllowlist() {
        return testClientEmailAllowlist;
    }

    public void setTestClientEmailAllowlist(List<String> testClientEmailAllowlist) {
        this.testClientEmailAllowlist = testClientEmailAllowlist;
    }

    public ClientRegistry withTestClientEmailAllowlist(List<String> testClientEmailAllowlist) {
        this.testClientEmailAllowlist = testClientEmailAllowlist;
        return this;
    }

    @DynamoDbAttribute("ConsentRequired")
    public boolean isConsentRequired() {
        return consentRequired;
    }

    public void setConsentRequired(boolean consentRequired) {
        this.consentRequired = consentRequired;
    }

    public ClientRegistry withConsentRequired(boolean consentRequired) {
        this.consentRequired = consentRequired;
        return this;
    }

    @DynamoDbAttribute("Claims")
    public List<String> getClaims() {
        return claims;
    }

    public void setClaims(List<String> claims) {
        this.claims = claims;
    }

    public ClientRegistry withClaims(List<String> claims) {
        this.claims = claims;
        return this;
    }

    @DynamoDbAttribute("ClientType")
    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public ClientRegistry withClientType(String clientType) {
        this.clientType = clientType;
        return this;
    }

    @DynamoDbAttribute("IdentityVerificationSupported")
    public boolean isIdentityVerificationSupported() {
        return identityVerificationSupported;
    }

    public void setIdentityVerificationSupported(boolean identityVerificationSupported) {
        this.identityVerificationSupported = identityVerificationSupported;
    }

    public ClientRegistry withIdentityVerificationSupported(boolean identityVerificationSupported) {
        this.identityVerificationSupported = identityVerificationSupported;
        return this;
    }
}
