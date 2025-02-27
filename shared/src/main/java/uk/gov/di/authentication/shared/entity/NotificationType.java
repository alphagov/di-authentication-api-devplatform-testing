package uk.gov.di.authentication.shared.entity;

import uk.gov.di.authentication.shared.helpers.LocaleHelper.SupportedLanguage;
import uk.gov.di.authentication.shared.services.ConfigurationService;

import java.util.HashMap;
import java.util.Map;

public enum NotificationType implements TemplateAware {
    VERIFY_EMAIL(
            "VERIFY_EMAIL_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "VERIFY_EMAIL_TEMPLATE_ID_CY")),
    VERIFY_PHONE_NUMBER(
            "VERIFY_PHONE_NUMBER_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "VERIFY_PHONE_NUMBER_TEMPLATE_ID_CY")),
    MFA_SMS("MFA_SMS_TEMPLATE_ID", Map.of(SupportedLanguage.CY, "MFA_SMS_TEMPLATE_ID_CY")),
    PASSWORD_RESET_CONFIRMATION(
            "PASSWORD_RESET_CONFIRMATION_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "PASSWORD_RESET_CONFIRMATION_TEMPLATE_ID_CY")),
    PASSWORD_RESET_CONFIRMATION_SMS(
            "PASSWORD_RESET_CONFIRMATION_SMS_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "PASSWORD_RESET_CONFIRMATION_SMS_TEMPLATE_ID_CY")),
    ACCOUNT_CREATED_CONFIRMATION(
            "ACCOUNT_CREATED_CONFIRMATION_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "ACCOUNT_CREATED_CONFIRMATION_TEMPLATE_ID_CY")),
    RESET_PASSWORD_WITH_CODE(
            "RESET_PASSWORD_WITH_CODE_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "RESET_PASSWORD_WITH_CODE_TEMPLATE_ID_CY")),
    VERIFY_CHANGE_HOW_GET_SECURITY_CODES(
            "VERIFY_CHANGE_HOW_GET_SECURITY_CODES_TEMPLATE_ID",
            Map.of(SupportedLanguage.CY, "VERIFY_CHANGE_HOW_GET_SECURITY_CODES_TEMPLATE_ID_CY"));

    private final String templateName;

    private Map<SupportedLanguage, String> languageSpecificTemplates = new HashMap<>();

    NotificationType(String templateName) {
        this.templateName = templateName;
    }

    NotificationType(
            String templateName, Map<SupportedLanguage, String> languageSpecificTemplates) {
        this(templateName);
        this.languageSpecificTemplates = languageSpecificTemplates;
    }

    public String getTemplateId(
            SupportedLanguage language, ConfigurationService configurationService) {
        String templateId = configurationService.getNotifyTemplateId(getTemplateName(language));
        if (!configurationService.isNotifyTemplatePerLanguage()
                || templateId == null
                || templateId.length() == 0) {
            return configurationService.getNotifyTemplateId(templateName);
        } else {
            return templateId;
        }
    }

    String getTemplateName(SupportedLanguage language) {
        return languageSpecificTemplates.getOrDefault(language, templateName);
    }
}
