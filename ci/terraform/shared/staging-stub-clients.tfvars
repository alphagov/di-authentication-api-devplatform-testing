stub_rp_clients = [
  {
    client_name = "di-auth-stub-relying-party-staging"
    callback_urls = [
      "https://di-auth-stub-relying-party-staging.london.cloudapps.digital/oidc/authorization-code/callback",
    ]
    logout_urls = [
      "https://di-auth-stub-relying-party-staging.london.cloudapps.digital/signed-out",
    ]
    test_client = "0"
    client_type = "web"
    scopes = [
      "openid",
      "email",
      "phone",
    ]
  },
  {
    client_name = "di-auth-stub-relying-party-staging-app"
    callback_urls = [
      "https://di-auth-stub-relying-party-staging-app.london.cloudapps.digital/oidc/authorization-code/callback",
    ]
    logout_urls = [
      "https://di-auth-stub-relying-party-staging-app.london.cloudapps.digital/signed-out",
    ]
    test_client = "1"
    client_type = "app"
    scopes = [
      "openid",
      "doc-checking-app",
    ]
  },
]