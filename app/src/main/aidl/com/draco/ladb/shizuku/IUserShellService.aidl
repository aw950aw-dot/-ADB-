package com.draco.ladb.shizuku;

interface IUserShellService {
    void destroy() = 16777114;
    String execute(String command, long timeoutMillis) = 2;
}
