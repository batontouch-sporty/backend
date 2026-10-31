package com.example.sporty.notification;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.sporty.notification.commons.config.AuthHeaders;
import com.example.sporty.notification.notifications.controller.NotificationController;
import com.example.sporty.notification.notifications.service.NotificationService;

// 처리하지 않은 예외도 {code, message}로 답하고, 404·405 같은 표준 상태는 바꾸지 않는지 본다.
@WebMvcTest(NotificationController.class)
class NotificationErrorResponseTest {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationService notificationService;

    @Test
    void unexpectedErrorReturns500WithoutInternalMessage() throws Exception {
        when(notificationService.getUnreadCount(anyLong())).thenThrow(new IllegalStateException("db password=secret"));
        mvc.perform(get("/api/notifications/me/unread-count").header(AuthHeaders.USER_ID, 1))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void standardMvcErrorsKeepTheirStatus() throws Exception {
        mvc.perform(post("/api/notifications/me").header(AuthHeaders.USER_ID, 1))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/no-such-path").header(AuthHeaders.USER_ID, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
