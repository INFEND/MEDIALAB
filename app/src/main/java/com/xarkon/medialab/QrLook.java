package com.xarkon.medialab;

/** Shared presentation tokens for the QR LINK-inspired MediaLAB visual skin.
 *  This class contains no conversion or job-processing logic. */
public final class QrLook {
    private QrLook() { }
    // First seven indexes retain MediaLAB 1.2.6 preference compatibility.
    public static final String[] NAMES = {
        "Лазурный", "Синий", "Фиолетовый", "Изумрудный", "Янтарный", "Розовый", "Красный",
        "Бирюзовый", "Мятный", "Морская волна", "Индиго", "Лавандовый", "Сиреневый",
        "Пурпурный", "Фуксия", "Малиновый", "Коралловый", "Оранжевый", "Золотой",
        "Лаймовый", "Оливковый", "Небесный", "Ледяной", "Графитовый"
    };
    public static final int[] COLORS = {
        0xff80a8ff,0xff57c7ff,0xffab86ff,0xff62d5b3,0xffffbe65,0xffff8cbc,0xffff786f,
        0xff22c8c7,0xff79e5c3,0xff36bfa4,0xff6f82f9,0xffbba5ff,0xffb97afe,
        0xffad66f1,0xffe36ac7,0xffee5a91,0xffff8977,0xffffa65d,0xffe9c46a,
        0xffb5da65,0xffa8b66d,0xff74c9f9,0xff9cdae9,0xff9ca9c5
    };
    public static int accent(int position) {
        return COLORS[Math.min(Math.max(position,0),COLORS.length-1)];
    }
}
