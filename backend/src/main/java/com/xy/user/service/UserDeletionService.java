package com.xy.user.service;

import com.xy.user.model.UserActor;

public interface UserDeletionService {
    boolean deleteUser(long id, UserActor loginUser);
}
