package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {
    private AuthService auth;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        auth = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth)).build();
    }

    @Test void signupCreatesAnAccountAndReturnsSignedToken() throws Exception {
        when(auth.signUp("A User", "a@example.com", "a-user", "correct horse battery"))
                .thenReturn(new AuthService.AuthResponse("jwt-token", new AuthUser("id-1", "A User", "a@example.com", "ADMIN")));
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A User\",\"email\":\"a@example.com\",\"username\":\"a-user\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.user.email").value("a@example.com"));
    }

    @Test void signinReturnsJwtForValidCredentials() throws Exception {
        when(auth.signIn("a@example.com", "correct horse battery"))
                .thenReturn(new AuthService.AuthResponse("jwt-token", new AuthUser("id-1", "A User", "a@example.com", "ADMIN")));
        mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test void signinReturnsUnauthorizedForInvalidCredentials() throws Exception {
        when(auth.signIn(anyString(), anyString())).thenThrow(new AuthService.AuthException(401, "Email or password is incorrect."));
        mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"wrong password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.message").exists());
    }

    @Test void signupRejectsNullJsonBodyWithoutReturningServerError() throws Exception {
        when(auth.signUp(isNull(), isNull(), isNull(), isNull())).thenThrow(new AuthService.AuthException(400, "Enter a valid name."));
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test void signupIsClosedAfterTheFirstAdministrator() throws Exception {
        when(auth.hasAdmins()).thenReturn(true);
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A User\",\"email\":\"a@example.com\",\"username\":\"a-user\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").exists());
        verify(auth, never()).signUp(any(), any(), any(), any());
    }

    @Test void bootstrapReportsWhetherPublicSignupIsOpen() throws Exception {
        when(auth.hasAdmins()).thenReturn(true);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signup_open").value(false));
    }
}
