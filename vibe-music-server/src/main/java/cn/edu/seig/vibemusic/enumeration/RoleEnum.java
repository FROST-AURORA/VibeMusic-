package cn.edu.seig.vibemusic.enumeration;

import lombok.Getter;

@Getter
public enum RoleEnum {
    //带备注
    ADMIN("ROLE_ADMIN"),
    USER("ROLE_USER");

    private final String role;

    RoleEnum(String role) {
        this.role = role;
    }

}
