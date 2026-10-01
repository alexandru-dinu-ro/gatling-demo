package com.example.tests.api.performance.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class HttpTokenSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void validResponseIsParsed() {
        AccessToken token = HttpTokenSource.parse(mapper,
                "{\"access_token\":\"authToken\",\"token_type\":\"Bearer\",\"expires_in\":900}");

        assertEquals(token.value(), "authToken");
        assertEquals(token.expiresInSeconds(), 900L);
    }

    @DataProvider
    public Object[][] invalidBodies() {
        return new Object[][]{
                {"not json at all", "token response is not valid JSON"},
                {"{\"expires_in\":900}", "token response has no access_token"},
                {"{\"access_token\":\"\",\"expires_in\":900}", "token response has no access_token"},
                {"{\"access_token\":42,\"expires_in\":900}", "token response has no access_token"},
                {"{\"access_token\":\"authToken\"}", "token response has no valid expires_in"},
                {"{\"access_token\":\"authToken\",\"expires_in\":0}", "token response has no valid expires_in"},
                {"{\"access_token\":\"authToken\",\"expires_in\":\"soon\"}", "token response has no valid expires_in"},
        };
    }

    @Test(dataProvider = "invalidBodies")
    public void invalidResponseIsRejected(String body, String expectedMessage) {
        TokenException error = expectThrows(TokenException.class, () -> HttpTokenSource.parse(mapper, body));

        assertEquals(error.getMessage(), expectedMessage);
    }
}
