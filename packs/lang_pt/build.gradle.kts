plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("lang_pt")
    dynamicDelivery { deliveryType.set("on-demand") }
}
