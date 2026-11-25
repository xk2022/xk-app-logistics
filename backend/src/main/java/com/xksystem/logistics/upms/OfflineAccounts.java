package com.xksystem.logistics.upms;

import com.xksystem.logistics.upms.internal.UpmsService;
import java.io.Console;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** Human terminal only. Not a startup runner, HTTP endpoint, seed profile, or environment toggle. */
@Component
public class OfflineAccounts {
    private final UpmsService service;
    public OfflineAccounts(UpmsService service) { this.service=service; }
    public void run(String command, Console console) {
        if (console == null) throw new IllegalStateException("Interactive console required");
        String operator=console.readLine("操作者識別（須經責任人核實）：");
        String reason=console.readLine("操作原因（勿輸入秘密）：");
        String username=console.readLine("目標帳號：");
        String name=command.equals("init") ? console.readLine("顯示名稱：") : null;
        char[] password=console.readPassword("臨時密碼（15–128 字元，輸入隱藏）：");
        char[] confirmation=console.readPassword("再次輸入臨時密碼：");
        try {
            if (password == null || !Arrays.equals(password, confirmation)) throw new IllegalArgumentException("密碼確認不一致，未執行");
            if (command.equals("init")) service.bootstrap(username, name, new String(password), operator, reason);
            else if (command.equals("recover")) service.recover(username, new String(password), operator, reason);
            else throw new IllegalArgumentException("未知命令");
            console.printf("操作完成。臨時密碼 15 分鐘內有效，首次登入須改密；請由真人安全交付，勿貼入紀錄。%n");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
