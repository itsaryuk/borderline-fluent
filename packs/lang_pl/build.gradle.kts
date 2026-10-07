plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_pl")
    dynamicDelivery { deliveryType.set("on-demand") }
}
