plugins { id("com.android.asset-pack") }
// Models are staged by scripts/stage-models.sh for release bundles; dev builds ship only the placeholder and get
// the models from scripts/push.sh instead.
assetPack {
    packName.set("core_whisper")
    dynamicDelivery { deliveryType.set("on-demand") }
}
