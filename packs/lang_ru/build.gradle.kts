plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_ru")
    dynamicDelivery { deliveryType.set("on-demand") }
}
