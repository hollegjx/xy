package com.xy.app.service.impl;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.service.AppService;
import com.xy.exception.BusinessException;
import com.xy.user.model.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AppServiceImpl implements AppService {

    private final AppMapper appMapper;

    public AppServiceImpl(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Override
    public long createApp(AppAddRequest request, User loginUser) {
        String prompt = request.initPrompt();
        int nameLength = Math.min(12, prompt.codePointCount(0, prompt.length()));
        CodeGenTypeEnum codeGenType = request.codeGenType() == null
                ? CodeGenTypeEnum.MULTI_FILE : request.codeGenType();

        App app = new App();
        app.setUserId(loginUser.getId());
        app.setAppName(prompt.substring(0, prompt.offsetByCodePoints(0, nameLength)));
        app.setInitPrompt(prompt);
        app.setCodeGenType(codeGenType.getValue());
        if (appMapper.insert(app) != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "创建应用失败，请稍后重试");
        }
        return app.getId();
    }
}
