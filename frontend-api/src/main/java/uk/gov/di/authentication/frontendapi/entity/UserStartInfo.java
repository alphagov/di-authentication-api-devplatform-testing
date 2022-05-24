package uk.gov.di.authentication.frontendapi.entity;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import jakarta.validation.constraints.NotNull;

public class UserStartInfo {

    @SerializedName("consentRequired")
    @Expose
    @NotNull
    private boolean consentRequired;

    @SerializedName("upliftRequired")
    @Expose
    @NotNull
    private boolean upliftRequired;

    @SerializedName("identityRequired")
    @Expose
    @NotNull
    private boolean identityRequired;

    @SerializedName("authenticated")
    @Expose
    @NotNull
    private boolean authenticated;

    @SerializedName("cookieConsent")
    @Expose
    private String cookieConsent;

    @SerializedName("gaCrossDomainTrackingId")
    @Expose
    private String gaCrossDomainTrackingId;

    @SerializedName("docCheckingAppUser")
    @Expose
    private boolean docCheckingAppUser;

    public UserStartInfo() {}

    public UserStartInfo(
            boolean consentRequired,
            boolean upliftRequired,
            boolean identityRequired,
            boolean authenticated,
            String cookieConsent,
            String gaCrossDomainTrackingId,
            boolean docCheckingAppUser) {
        this.consentRequired = consentRequired;
        this.upliftRequired = upliftRequired;
        this.identityRequired = identityRequired;
        this.authenticated = authenticated;
        this.cookieConsent = cookieConsent;
        this.gaCrossDomainTrackingId = gaCrossDomainTrackingId;
        this.docCheckingAppUser = docCheckingAppUser;
    }

    public boolean isConsentRequired() {
        return consentRequired;
    }

    public boolean isUpliftRequired() {
        return upliftRequired;
    }

    public boolean isIdentityRequired() {
        return identityRequired;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getCookieConsent() {
        return cookieConsent;
    }

    public String getGaCrossDomainTrackingId() {
        return gaCrossDomainTrackingId;
    }

    public boolean isDocCheckingAppUser() {
        return docCheckingAppUser;
    }
}
