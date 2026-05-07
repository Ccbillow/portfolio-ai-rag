package com.simon.rag.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.rag.domain.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    @Select("SELECT * FROM prompt_template WHERE name = #{name}")
    PromptTemplate findByName(@Param("name") String name);

    @Update("UPDATE prompt_template SET content = #{content}, version = version + 1, updated_at = NOW() WHERE name = #{name}")
    int updateContentByName(@Param("name") String name, @Param("content") String content);
}
