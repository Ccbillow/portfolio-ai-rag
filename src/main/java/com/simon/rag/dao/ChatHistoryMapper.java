package com.simon.rag.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.rag.domain.entity.ChatHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {
}
