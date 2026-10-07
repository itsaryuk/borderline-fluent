plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_ar")
    dynamicDelivery { deliveryType.set("on-demand") }
}
