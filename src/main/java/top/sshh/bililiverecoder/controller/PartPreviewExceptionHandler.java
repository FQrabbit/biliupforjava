package top.sshh.bililiverecoder.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@Slf4j
@RestControllerAdvice(assignableTypes = PartPreviewController.class)
public class PartPreviewExceptionHandler {

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncRequestTimeout(AsyncRequestTimeoutException e,
                                                          HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("[BLR] Part preview stream timed out normally: {}", request.getRequestURI());
        }
        return ResponseEntity.noContent().build();
    }
}
