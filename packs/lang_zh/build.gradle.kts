plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_zh")
    dynamicDelivery { deliveryType.set("on-demand") }
}
