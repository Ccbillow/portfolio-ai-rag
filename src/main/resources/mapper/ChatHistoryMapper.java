package mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yourname.rag.domain.entity.ChatHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * Chat history mapper.
 * Complex analytics queries (top questions, avg latency) go in ChatHistoryMapper.xml.
 */
@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {
}