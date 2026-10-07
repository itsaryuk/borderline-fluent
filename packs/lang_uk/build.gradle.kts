plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_uk")
    dynamicDelivery { deliveryType.set("on-demand") }
}
