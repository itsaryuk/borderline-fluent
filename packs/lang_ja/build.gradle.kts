plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_ja")
    dynamicDelivery { deliveryType.set("on-demand") }
}
