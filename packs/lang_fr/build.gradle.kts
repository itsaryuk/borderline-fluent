plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_fr")
    dynamicDelivery { deliveryType.set("on-demand") }
}
