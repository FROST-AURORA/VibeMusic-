package cn.edu.seig.vibemusic.util;

import java.util.Random;

public class RandomCodeUtil {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 6; // 生成的字符串长度
    private static final Random random = new Random();

    /**
     * 生成随机的6个字符的字符串
     *
     * @return 随机字符串
     */
    public static String generateRandomCode() {
        StringBuilder stringBuilder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            //random.nextInt(n)：生成 [0, n) 范围内的随机整数
            int index = random.nextInt(CHARACTERS.length());
            //CHARACTERS.charAt(index)：从字符集中取出对应位置的字符
            stringBuilder.append(CHARACTERS.charAt(index));
        }
        return stringBuilder.toString();
    }

}
