package uk.gov.di.authentication.oidc.services;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ResponseMode;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.OIDCClaimsRequest;
import com.nimbusds.openid.connect.sdk.claims.ClaimsSetRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.oidc.entity.AuthRequestError;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ValidClaims;
import uk.gov.di.authentication.shared.entity.ValidScopes;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoClientService;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.LogFieldName.CLIENT_ID;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachLogFieldToLogs;

public class AuthorizationService {

    public static final String VTR_PARAM = "vtr";
    private final DynamoClientService dynamoClientService;
    private final IPVCapacityService ipvCapacityService;
    private static final Logger LOG = LogManager.getLogger(AuthorizationService.class);

    public AuthorizationService(
            DynamoClientService dynamoClientService, IPVCapacityService ipvCapacityService) {
        this.dynamoClientService = dynamoClientService;
        this.ipvCapacityService = ipvCapacityService;
    }

    public AuthorizationService(ConfigurationService configurationService) {
        this(
                new DynamoClientService(configurationService),
                new IPVCapacityService(configurationService));
    }

    public boolean isClientRedirectUriValid(ClientID clientID, URI redirectURI)
            throws ClientNotFoundException {
        Optional<ClientRegistry> client = dynamoClientService.getClient(clientID.toString());
        if (client.isEmpty()) {
            throw new ClientNotFoundException(clientID.toString());
        }
        return client.get().getRedirectUrls().contains(redirectURI.toString());
    }

    public AuthenticationSuccessResponse generateSuccessfulAuthResponse(
            AuthenticationRequest authRequest,
            AuthorizationCode authorizationCode,
            URI redirectUri,
            State state) {

        LOG.info("Generating Successful Auth Response");
        return new AuthenticationSuccessResponse(
                redirectUri,
                authorizationCode,
                null,
                null,
                state,
                null,
                authRequest.getResponseMode());
    }

    public Optional<AuthRequestError> validateAuthRequest(
            AuthenticationRequest authRequest, boolean isNonceRequired) {
        var clientId = authRequest.getClientID().toString();

        attachLogFieldToLogs(CLIENT_ID, clientId);

        Optional<ClientRegistry> client = dynamoClientService.getClient(clientId);

        if (client.isEmpty()) {
            var errorMsg = "No Client found with given ClientID";
            LOG.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        if (!client.get().getRedirectUrls().contains(authRequest.getRedirectionURI().toString())) {
            LOG.warn("Invalid Redirect URI in request {}", authRequest.getRedirectionURI());
            throw new RuntimeException(
                    format(
                            "Invalid Redirect in request %s",
                            authRequest.getRedirectionURI().toString()));
        }
        var redirectURI = authRequest.getRedirectionURI();
        if (authRequest.getRequestURI() != null) {
            LOG.error("Request URI is not supported");
            return Optional.of(
                    new AuthRequestError(OAuth2Error.REQUEST_URI_NOT_SUPPORTED, redirectURI));
        }
        if (authRequest.getRequestObject() != null) {
            LOG.error("Request object not expected here");
            return Optional.of(
                    new AuthRequestError(OAuth2Error.REQUEST_NOT_SUPPORTED, redirectURI));
        }
        if (!authRequest.getResponseType().toString().equals(ResponseType.CODE.toString())) {
            LOG.error(
                    "Unsupported responseType included in request. Expected responseType of code");
            return Optional.of(
                    new AuthRequestError(OAuth2Error.UNSUPPORTED_RESPONSE_TYPE, redirectURI));
        }
        if (!areScopesValid(authRequest.getScope().toStringList(), client.get())) {
            return Optional.of(new AuthRequestError(OAuth2Error.INVALID_SCOPE, redirectURI));
        }
        if (!areClaimsValid(authRequest.getOIDCClaims(), client.get())) {
            return Optional.of(
                    new AuthRequestError(
                            new ErrorObject(
                                    OAuth2Error.INVALID_REQUEST_CODE,
                                    "Request contains invalid claims"),
                            redirectURI));
        }
        if (authRequest.getNonce() == null && isNonceRequired) {
            LOG.error("Nonce is missing from authRequest");
            return Optional.of(
                    new AuthRequestError(
                            new ErrorObject(
                                    OAuth2Error.INVALID_REQUEST_CODE,
                                    "Request is missing nonce parameter"),
                            redirectURI));
        }
        if (authRequest.getState() == null) {
            LOG.error("State is missing from authRequest");
            return Optional.of(
                    new AuthRequestError(
                            new ErrorObject(
                                    OAuth2Error.INVALID_REQUEST_CODE,
                                    "Request is missing state parameter"),
                            redirectURI));
        }
        List<String> authRequestVtr = authRequest.getCustomParameter(VTR_PARAM);
        try {
            var vectorOfTrust = VectorOfTrust.parseFromAuthRequestAttribute(authRequestVtr);
            if (vectorOfTrust.containsLevelOfConfidence()
                    && !ipvCapacityService.isIPVCapacityAvailable()
                    && !client.get().isTestClient()) {
                return Optional.of(
                        new AuthRequestError(OAuth2Error.TEMPORARILY_UNAVAILABLE, redirectURI));
            }
        } catch (IllegalArgumentException e) {
            LOG.error(
                    "vtr in AuthRequest is not valid. vtr in request: {}. IllegalArgumentException: {}",
                    authRequestVtr,
                    e);
            return Optional.of(
                    new AuthRequestError(
                            new ErrorObject(
                                    OAuth2Error.INVALID_REQUEST_CODE, "Request vtr not valid"),
                            redirectURI));
        }
        return Optional.empty();
    }

    public AuthenticationErrorResponse generateAuthenticationErrorResponse(
            AuthenticationRequest authRequest,
            ErrorObject errorObject,
            URI redirectUri,
            State state) {

        return generateAuthenticationErrorResponse(
                redirectUri, state, authRequest.getResponseMode(), errorObject);
    }

    public AuthenticationErrorResponse generateAuthenticationErrorResponse(
            URI redirectUri, State state, ResponseMode responseMode, ErrorObject errorObject) {
        LOG.info("Generating Authentication Error Response");
        return new AuthenticationErrorResponse(redirectUri, errorObject, state, responseMode);
    }

    public VectorOfTrust getEffectiveVectorOfTrust(AuthenticationRequest authenticationRequest) {
        return VectorOfTrust.parseFromAuthRequestAttribute(
                authenticationRequest.getCustomParameter(VTR_PARAM));
    }

    private boolean areScopesValid(List<String> scopes, ClientRegistry clientRegistry) {
        for (String scope : scopes) {
            if (ValidScopes.getAllValidScopes().stream().noneMatch(t -> t.equals(scope))) {
                LOG.error(
                        "Scopes have been requested which are not yet supported. Scopes in request: {}",
                        scopes);
                return false;
            }
        }
        if (!clientRegistry.getScopes().containsAll(scopes)) {
            LOG.error(
                    "Scopes have been requested which this client is not supported to request. Scopes in request: {}",
                    scopes);
            return false;
        }
        return true;
    }

    private boolean areClaimsValid(OIDCClaimsRequest claimsRequest, ClientRegistry clientRegistry) {
        if (claimsRequest == null) {
            LOG.info("No claims present in auth request");
            return true;
        }
        List<String> claimNames =
                claimsRequest.getUserInfoClaimsRequest().getEntries().stream()
                        .map(ClaimsSetRequest.Entry::getClaimName)
                        .collect(Collectors.toList());
        for (String claim : claimNames) {
            if (ValidClaims.getAllValidClaims().stream().noneMatch(t -> t.equals(claim))) {
                LOG.error(
                        "Claims have been requested which are not yet supported. Claims in request: {}",
                        claimsRequest.toJSONString());
                return false;
            }
        }
        if (!clientRegistry.getClaims().containsAll(claimNames)) {
            LOG.error(
                    "Claims have been requested which this client is not supported to request. Claims in request: {}",
                    claimsRequest.toJSONString());
            return false;
        }
        LOG.info("Claims are present AND valid in auth request");
        return true;
    }

    public String getExistingOrCreateNewPersistentSessionId(Map<String, String> headers) {
        return PersistentIdHelper.getExistingOrCreateNewPersistentSessionId(headers);
    }

    public boolean isTestJourney(ClientID clientID, String emailAddress) {
        var isTestJourney = dynamoClientService.isTestJourney(clientID.toString(), emailAddress);
        LOG.info("Is journey a test journey: {}", isTestJourney);
        return isTestJourney;
    }
}
