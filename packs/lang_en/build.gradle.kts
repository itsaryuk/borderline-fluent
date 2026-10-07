plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_en")
    dynamicDelivery { deliveryType.set("on-demand") }
}
