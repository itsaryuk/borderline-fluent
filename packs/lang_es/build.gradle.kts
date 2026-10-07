plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_es")
    dynamicDelivery { deliveryType.set("on-demand") }
}
