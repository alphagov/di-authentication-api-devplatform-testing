package uk.gov.di.authentication.shared.entity;

import uk.gov.di.authentication.shared.helpers.LocaleHelper.SupportedLanguage;
import uk.gov.di.authentication.shared.services.ConfigurationService;

public enum DeliveryReceiptsNotificationType implements TemplateAware {
    VERIFY_EMAIL("VERIFY_EMAIL", "VERIFY_EMAIL_TEMPLATE_ID"),
    RESET_PASSWORD("RESET_PASSWORD_EMAIL", "RESET_PASSWORD_TEMPLATE_ID"),
    PASSWORD_RESET_CONFIRMATION(
            "PASSWORD_RESET_CONFIRMATION_EMAIL", "PASSWORD_RESET_CONFIRMATION_TEMPLATE_ID"),
    PASSWORD_RESET_CONFIRMATION_SMS(
            "PASSWORD_RESET_CONFIRMATION_SMS", "PASSWORD_RESET_CONFIRMATION_SMS_TEMPLATE_ID"),
    ACCOUNT_CREATED_CONFIRMATION(
            "ACCOUNT_CREATED_CONFIRMATION_EMAIL", "ACCOUNT_CREATED_CONFIRMATION_TEMPLATE_ID"),
    RESET_PASSWORD_WITH_CODE(
            "RESET_PASSWORD_WITH_CODE_EMAIL", "RESET_PASSWORD_WITH_CODE_TEMPLATE_ID"),
    VERIFY_CHANGE_HOW_GET_SECURITY_CODES(
            "VERIFY_CHANGE_HOW_GET_SECURITY_CODES_EMAIL",
            "VERIFY_CHANGE_HOW_GET_SECURITY_CODES_TEMPLATE_ID"),
    EMAIL_UPDATED("EMAIL_UPDATED_EMAIL", "EMAIL_UPDATED_TEMPLATE_ID"),
    DELETE_ACCOUNT("DELETE_ACCOUNT_EMAIL", "DELETE_ACCOUNT_TEMPLATE_ID"),
    PHONE_NUMBER_UPDATED("PHONE_NUMBER_UPDATED_EMAIL", "PHONE_NUMBER_UPDATED_TEMPLATE_ID"),
    PASSWORD_UPDATED("PASSWORD_UPDATED_EMAIL", "PASSWORD_UPDATED_TEMPLATE_ID"),
    VERIFY_PHONE_NUMBER("VERIFY_PHONE_NUMBER_SMS", "VERIFY_PHONE_NUMBER_TEMPLATE_ID"),
    MFA_SMS("MFA_SMS", "MFA_SMS_TEMPLATE_ID"),
    AM_VERIFY_EMAIL("AM_VERIFY_EMAIL", "AM_VERIFY_EMAIL_TEMPLATE_ID"),
    AM_VERIFY_PHONE_NUMBER("AM_VERIFY_PHONE_NUMBER_SMS", "AM_VERIFY_PHONE_NUMBER_TEMPLATE_ID");

    private final String templateAlias;
    private final String templateName;

    DeliveryReceiptsNotificationType(String templateAlias, String templateName) {
        this.templateAlias = templateAlias;
        this.templateName = templateName;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateId() {
        return System.getenv(templateName);
    }

    @Override
    public String getTemplateId(
            SupportedLanguage language, ConfigurationService configurationService) {
        return null;
    }

    public String getTemplateAlias() {
        return templateAlias;
    }
}
