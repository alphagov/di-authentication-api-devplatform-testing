package uk.gov.di.authentication.sharedtest.helper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.validation.AuthAppCodeValidator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthAppStubTest {
    private AuthAppStub authAppStub;
    private AuthAppCodeValidator authAppCodeValidator;
    private static ConfigurationService configurationService;

    @BeforeAll
    static void init() {
        configurationService = mock(ConfigurationService.class);
        when(configurationService.getAuthAppCodeWindowLength()).thenReturn(30);
    }

    @BeforeEach
    void setUp() {
        this.authAppStub = new AuthAppStub();
        this.authAppCodeValidator =
                new AuthAppCodeValidator(
                        "test-email@test.com",
                        mock(CodeStorageService.class),
                        configurationService,
                        mock(DynamoService.class),
                        99999);
    }

    @Test
    void worksWithAuthAppCodeValidatorAlgorithm() {
        String generatedCode =
                authAppStub.getAuthAppOneTimeCode(
                        "ABCDAAWOXKUQCDH5QMSPHAGJXMTXFZRZAKFTR6Y3Q5YRN5EVOYRQ");

        assertTrue(
                authAppCodeValidator.isCodeValid(
                        generatedCode, "ABCDAAWOXKUQCDH5QMSPHAGJXMTXFZRZAKFTR6Y3Q5YRN5EVOYRQ"));
    }
}
