package com.simon.rag.service;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;

public interface AuthService {
    Vos.LoginResponse login(Dtos.LoginRequest request);
}
