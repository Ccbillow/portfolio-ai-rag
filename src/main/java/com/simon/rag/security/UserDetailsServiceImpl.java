package com.simon.rag.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.rag.dao.SysUserMapper;
import com.simon.rag.domain.entity.SysUser;
import com.simon.rag.security.JwtUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getDeleted, 0));
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return new JwtUserDetails(user);
    }
}
