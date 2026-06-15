package tw.edu.ntub.imd.birc.campus.activity.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import tw.edu.ntub.imd.birc.campus.activity.config.util.JwtProvider;
import tw.edu.ntub.imd.birc.campus.activity.databaseconfig.dao.UserDAO;
import tw.edu.ntub.imd.birc.campus.activity.databaseconfig.entity.User;
import tw.edu.ntub.imd.birc.campus.activity.databaseconfig.enumerate.IdentityRole;
import tw.edu.ntub.imd.birc.campus.activity.exception.SsoException;
import tw.edu.ntub.imd.birc.campus.activity.service.AuthService;
import tw.edu.ntub.imd.birc.campus.activity.vo.SsoLoginResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserDAO userDAO;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${sso.base-url}")
    private String ssoBaseUrl;

    @Value("${sso.client-id}")
    private String ssoClientId;

    @Value("${sso.client-secret}")
    private String ssoClientSecret;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SsoLoginResult ssoLogin(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new SsoException("missing_code", HttpStatus.BAD_REQUEST);
        }

        try {
            String ssoVerifyEndpoint = ssoBaseUrl + "/sso/verify-code";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set("X-Client-Id", ssoClientId);
            log.error("準備送出的 ClientId: [{}], Secret: [{}]", ssoClientId, ssoClientSecret);
            headers.set("X-Client-Secret", ssoClientSecret);
            HttpEntity<String> requestEntity = new HttpEntity<>(code, headers);

            log.info("========== 準備打向 SSO 的 URL 是: {} ==========", ssoVerifyEndpoint);
            ResponseEntity<String> ssoResponse =
                    restTemplate.exchange(ssoVerifyEndpoint, HttpMethod.POST, requestEntity, String.class);

            String ssoRespBody = ssoResponse.getBody();
            if (ssoRespBody == null || ssoRespBody.isEmpty()) {
                throw new SsoException("sso_empty_response", HttpStatus.BAD_GATEWAY);
            }

            Map<String, Object> payload = objectMapper.readValue(ssoRespBody, new TypeReference<Map<String, Object>>() {});
            if (payload.containsKey("error")) {
                String err = String.valueOf(payload.get("error"));
                if ("invalid_or_expired_code".equals(err)) {
                    throw new SsoException("unauthorized", HttpStatus.UNAUTHORIZED);
                }
                throw new SsoException("bad_request", HttpStatus.BAD_REQUEST);
            }

            String email = (String) payload.get("email");
            String name = (String) payload.get("name");
            String picture = (String) payload.get("picture");

            if (email == null || email.trim().isEmpty()) {
                throw new SsoException("sso_missing_info", HttpStatus.BAD_GATEWAY);
            }

            User user = userDAO.findBySchoolEmail(email)
                    .map(existingUser -> {
                        boolean updated = false;
                        if (name != null && !name.equals(existingUser.getName())) {
                            existingUser.setName(name);
                            updated = true;
                        }
                        if (picture != null && !picture.equals(existingUser.getPicture())) {
                            existingUser.setPicture(picture);
                            updated = true;
                        }
                        if (updated) {
                            return userDAO.save(existingUser);
                        }
                        return existingUser;
                    })
                    .map(existingUser -> syncUserProfile(existingUser, name, picture))
                    .orElseGet(() -> createNewUser(email, name, picture));

            String myAppToken = jwtProvider.generateToken(user);

            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("email", email);
            userInfo.put("name", name != null ? name : "");
            userInfo.put("picture", picture != null ? picture : "");

            return SsoLoginResult.builder()
                    .authToken(myAppToken)
                    .userInfo(userInfo)
                    .build();

        } catch (SsoException e) {
            throw e;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse SSO response", e);
            throw new SsoException("sso_response_parse_error", HttpStatus.BAD_GATEWAY);
        } catch (HttpStatusCodeException e) {
            log.error("❌ SSO 伺服器拒絕了請求！狀態碼: {}", e.getStatusCode());
            log.error("❌ SSO 伺服器回傳的錯誤詳細內容: {}", e.getResponseBodyAsString());
            throw new SsoException("error_login", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("SSO exchange failed", e);
            throw new SsoException("error_login", HttpStatus.BAD_GATEWAY);
        }
    }

    private User syncUserProfile(User user, String name, String picture) {
        boolean hasChanges = false;

        if (name != null && !name.equals(user.getName())) {
            user.setName(name);
            hasChanges = true;
        }
        if (picture != null && !picture.equals(user.getPicture())) {
            user.setPicture(picture);
            hasChanges = true;
        }

        return hasChanges ? userDAO.save(user) : user;
    }

    private Map<String, String> buildUserInfo(User user) {
        Map<String, String> info = new HashMap<>();
        info.put("email", user.getSchoolEmail());
        info.put("name", Optional.ofNullable(user.getName()).orElse(""));
        info.put("picture", Optional.ofNullable(user.getPicture()).orElse(""));
        return info;
    }

    private User createNewUser(String email, String name, String picture) {
        User newUser = new User();
        newUser.setStudentId(email.split("@")[0]);
        newUser.setSchoolEmail(email);
        newUser.setName(name);
        newUser.setPicture(picture);
        newUser.setStatus(true);
        newUser.setIdentity(IdentityRole.TEACHER_STUDENT);
        return userDAO.save(newUser);
    }
}
