package tw.edu.ntub.imd.birc.campus.activity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tw.edu.ntub.imd.birc.campus.activity.service.AuthService;
import tw.edu.ntub.imd.birc.campus.activity.util.http.ResponseEntityBuilder;
import tw.edu.ntub.imd.birc.campus.activity.vo.SsoLoginResult;

@RestController
@RequestMapping("/auth-tokens")
@Tag(name = "Authentication", description = "身分驗證與授權管理 — 此群組下的端點不需要 Bearer Token")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "使用 SSO Code 交換 Token", description = "接收 SSO 登入中心回傳的 Code，交換為系統 JWT")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "登入成功"),
        @ApiResponse(responseCode = "400", description = "SSO Code無效或已過期"),
        @ApiResponse(responseCode = "401", description = "尚未登入或憑證已失效"),
        @ApiResponse(responseCode = "403", description = "權限不足，拒絕存取"),
        @ApiResponse(responseCode = "500", description = "伺服器內部錯誤")
    })
    @PostMapping("/exchange")
    public ResponseEntity<String> exchangeToken(
            @RequestHeader("X-Client-Token") String code, HttpServletResponse response) {
        SsoLoginResult loginResult = authService.ssoLogin(code);

        response.setHeader("X-Auth-Token", loginResult.getAuthToken());

        //        ObjectData responseData = new ObjectData();
        //        Map<String, String> userInfo = loginResult.getUserInfo();
        //        if (userInfo != null) {
        //            for (Map.Entry<String, String> entry : userInfo.entrySet()) {
        //                responseData.add(entry.getKey(), entry.getValue());
        //            }
        //        }

        return ResponseEntityBuilder.success()
                .message("登入成功")
                //                .data(responseData)
                .build();
    }
}
