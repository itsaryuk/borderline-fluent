plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_it")
    dynamicDelivery { deliveryType.set("on-demand") }
}
