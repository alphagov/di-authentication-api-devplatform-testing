package uk.gov.di.authentication.shared.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.di.authentication.shared.entity.CredentialTrustLevel.LOW_LEVEL;
import static uk.gov.di.authentication.shared.entity.CredentialTrustLevel.MEDIUM_LEVEL;
import static uk.gov.di.authentication.sharedtest.helper.JsonArrayHelper.jsonArrayOf;

class VectorOfTrustTest {
    @Test
    void shouldParseValidStringWithSingleVector() {
        var jsonArray = jsonArrayOf("Cl.Cm");
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList(jsonArray));
        assertThat(vectorOfTrust.getCredentialTrustLevel(), equalTo(MEDIUM_LEVEL));
        assertNull(vectorOfTrust.getLevelOfConfidence());
    }

    @Test
    void shouldReturnDefaultVectorWhenEmptyListIsPassedIn() {
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(new ArrayList<>());
        assertThat(
                vectorOfTrust.getCredentialTrustLevel(),
                equalTo(CredentialTrustLevel.getDefault()));
        assertNull(vectorOfTrust.getLevelOfConfidence());
    }

    @Test
    void shouldReturnLowestVectorWhenMultipleSetsAreIsPassedIn() {
        var jsonArray = jsonArrayOf("Cl.Cm", "Cl");
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList(jsonArray));
        assertThat(vectorOfTrust.getCredentialTrustLevel(), equalTo(LOW_LEVEL));
        assertNull(vectorOfTrust.getLevelOfConfidence());
    }

    @Test
    void shouldParseValidStringWithMultipleVectors() {
        var jsonArray = jsonArrayOf("Cl");
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList(jsonArray));
        assertThat(vectorOfTrust.getCredentialTrustLevel(), equalTo(LOW_LEVEL));
        assertNull(vectorOfTrust.getLevelOfConfidence());
    }

    @Test
    void shouldParseValidStringWithSingleIdentityVector() {
        var jsonArray = jsonArrayOf("P2.Cl.Cm");
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList(jsonArray));
        assertThat(vectorOfTrust.getCredentialTrustLevel(), equalTo(MEDIUM_LEVEL));
        assertThat(vectorOfTrust.getLevelOfConfidence(), equalTo(LevelOfConfidence.MEDIUM_LEVEL));
    }

    @Test
    void shouldParseToLowCredentialTrustLevelAndMediumLevelOfConfidence() {
        var jsonArray = jsonArrayOf("P2.Cl.Cm", "P2.Cl");
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList(jsonArray));
        assertThat(vectorOfTrust.getCredentialTrustLevel(), equalTo(LOW_LEVEL));
        assertThat(vectorOfTrust.getLevelOfConfidence(), equalTo(LevelOfConfidence.MEDIUM_LEVEL));
    }

    @Test
    void shouldThrowWhenUnsupportedIdentityValueInVector() {
        var jsonArray = jsonArrayOf("P2.Cl.Cm", "P3.Cl");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VectorOfTrust.parseFromAuthRequestAttribute(
                                Collections.singletonList(jsonArray)));
    }

    @Test
    void shouldThrowWhenTooManyValuesInVector() {
        var jsonArray = jsonArrayOf("Cl.Cm.Cl");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VectorOfTrust.parseFromAuthRequestAttribute(
                                Collections.singletonList(jsonArray)));
    }

    @Test
    void shouldThrowWhenOnlyIdentityLevelIsSentInRequest() {
        var jsonArray = jsonArrayOf("P2");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VectorOfTrust.parseFromAuthRequestAttribute(
                                Collections.singletonList(jsonArray)));
    }

    @Test
    void shouldThrowWhenMultipleIdentityValuesArePresentInVector() {
        var jsonArray = jsonArrayOf("P1.Pb");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VectorOfTrust.parseFromAuthRequestAttribute(
                                Collections.singletonList(jsonArray)));
    }

    @Test
    void shouldThrowIfOnlyCmIsPresent() {
        var jsonArray = jsonArrayOf("Cm");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VectorOfTrust.parseFromAuthRequestAttribute(
                                Collections.singletonList(jsonArray)));
    }

    @Test
    void shouldThrowIfEmptyListIsPresent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VectorOfTrust.parseFromAuthRequestAttribute(Collections.singletonList("")));
    }

    @Test
    void shouldNotIncludeIdentityValuesInTokenWhenTheyArePresent() {
        String vectorString = "P2.Cl.Cm";

        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(
                        Collections.singletonList(jsonArrayOf(vectorString)));
        assertThat(vectorOfTrust.retrieveVectorOfTrustForToken(), equalTo("Cl.Cm"));
    }

    @Test
    void shouldReturnCorrectCredentialTrustLevelInToken() {
        String vectorString = "Cl.Cm";
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(
                        Collections.singletonList(jsonArrayOf(vectorString)));
        assertThat(vectorOfTrust.retrieveVectorOfTrustForToken(), equalTo(vectorString));
    }

    @Test
    void shouldReturnTrueWhenIdentityLevelOfConfidenceIsPresent() {
        String vectorString = "P2.Cl.Cm";
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(
                        Collections.singletonList(jsonArrayOf(vectorString)));
        assertTrue(vectorOfTrust.containsLevelOfConfidence());
    }

    @Test
    void shouldReturnFalseWhenIdentityLevelOfConfidenceIsNotPresent() {
        String vectorString = "Cl.Cm";
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(
                        Collections.singletonList(jsonArrayOf(vectorString)));
        assertFalse(vectorOfTrust.containsLevelOfConfidence());
    }

    @Test
    void shouldReturnFalseWhenIdentityLevelOfConfidenceIsP0() {
        String vectorString = "P0.Cl.Cm";
        VectorOfTrust vectorOfTrust =
                VectorOfTrust.parseFromAuthRequestAttribute(
                        Collections.singletonList(jsonArrayOf(vectorString)));
        assertFalse(vectorOfTrust.containsLevelOfConfidence());
    }

    @ParameterizedTest
    @MethodSource("equalityTests")
    void shouldReturnCorrectEquality(String one, String two, boolean areEqual) {
        var vtrOne = VectorOfTrust.parseFromAuthRequestAttribute(List.of(one));
        var vtrTwo = VectorOfTrust.parseFromAuthRequestAttribute(List.of(two));

        assertThat(vtrOne.equals(vtrTwo), equalTo(areEqual));
    }

    public static Stream<Arguments> equalityTests() {
        return Stream.of(
                Arguments.of("[\"P2.Cl\"]", "[\"Cl.P2\"]", true),
                Arguments.of("[\"P2.Cl.Cm\"]", "[\"Cl.Cm.P2\"]", true),
                Arguments.of("[\"P2.Cm.Cl\"]", "[\"Cl.Cm.P2\"]", true),
                Arguments.of("[\"Cm.Cl\"]", "[\"Cl.Cm\"]", true),
                Arguments.of("[\"Cl.Cm\"]", "[\"Cl.Cm.P2\"]", false),
                Arguments.of("[\"Cl.Cm\"]", "[\"P2.Cl\"]", false));
    }
}
