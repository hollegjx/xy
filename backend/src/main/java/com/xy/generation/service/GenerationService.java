package com.xy.generation.service;

import com.xy.generation.model.dto.GenerationRequest;
import com.xy.user.model.entity.User;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface GenerationService {

    SseEmitter generate(long appId, GenerationRequest request, User loginUser);
}
