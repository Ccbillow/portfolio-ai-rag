package com.simon.rag.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.rag.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * User mapper — inherits full CRUD from BaseMapper.
 * Complex queries go in UserMapper.xml under resources/mapper/.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}