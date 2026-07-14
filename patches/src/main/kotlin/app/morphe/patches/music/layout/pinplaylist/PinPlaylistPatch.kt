package app.morphe.patches.music.layout.pinplaylist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/pinplaylist/PinPlaylistPatch;"

/**
 * Matches MenuItemPresenter.onClick(View).
 *
 * The method resolves a menu item's command through Larbe and dispatches it through
 * Larzp. We intercept at the beginning, identify the current menu item by its icon
 * enum, and consume only Pin/Unpin Speed Dial clicks.
 */
internal object PlaylistMenuItemClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        methodCall("Larbe;->c(Lbwyn;)Lbqco;"),
        methodCall("Larbe;->b(Lbwyn;)Lbqco;"),
        methodCall("Larzp;->c(Lbqco;Ljava/util/Map;)V"),
    ),
)

internal object PlaylistMenuItemBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall("Lqpg;->b(Lbwzj;)Z"),
        methodCall("Larbe;->e(Lbwyn;)Ljava/lang/CharSequence;"),
        methodCall(
            "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"
        ),
    ),
)

/**
 * Matches hyz.o(holder, position), the Library RecyclerView/Litho adapter bind.
 */
internal object PlaylistLithoAdapterBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lvt;",
        "I",
    ),
    filters = listOf(
        methodCall("Lhvg;->d()Lhzq;"),
        methodCall("Lhzc;->q(Lhvg;)I"),
        methodCall("Lhzc;->p(Lhvg;)I"),
        methodCall("Lhvg;->b()Lcom/facebook/litho/ComponentTree;"),
    ),
)

/**
 * Matches bfrh.h(int), which converts one adapter source position into an
 * AdapterProxyRenderInfo (hyi).
 */
internal object PlaylistAdapterProxyRenderInfoFingerprint : Fingerprint(
    returnType = "Lhzq;",
    parameters = listOf("I"),
    filters = listOf(
        methodCall("Lhyi;-><init>(Lhut;)V"),
    ),
)

/**
 * Matches qot.j(menu, source, presenter, context), the flyout creation path.
 * This and the exact qot.k search near the bottom are the main flyout symbols
 * to re-check when updating supported YouTube Music versions.
 */
internal object PlaylistFlyoutSourceFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lbwyr;",
        "Ljava/lang/Object;",
        "Lqpg;",
        "Laryl;",
    ),
    filters = listOf(
        methodCall("Lbfge;->a(Lbwyr;Ljava/lang/Object;)Ljava/util/List;"),
        methodCall("Lqpc;->h(Lfu;Ljava/lang/String;)V"),
    ),
)

val pinPlaylistPatch = bytecodePatch(
    name = "Pin playlists",
    description = "Replaces Speed Dial pinning with persistent Library playlist pinning.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference(
                key = "morphe_music_replace_pin_to_speed_dial",
                summary = true
            ),
            SwitchPreference(
                key = "morphe_music_pin_playlist_separate_menu_item",
                summary = true
            )
        )

        /*
         * Part 1: intercept the existing Pin/Unpin Speed Dial flyout action.
         */
        val originalMethod = PlaylistMenuItemClickFingerprint.method
        val originalRegisterCount = originalMethod.implementation!!.registerCount

        // onClick(View) has two parameter registers: p0 and p1.
        val parameterRegisterCount = 2
        val firstNewLocalRegister = originalRegisterCount - parameterRegisterCount

        val patchedMethod = originalMethod.cloneMutable(additionalRegisters = 2)

        PlaylistMenuItemClickFingerprint.classDef.methods.apply {
            remove(originalMethod)
            add(patchedMethod)
        }

        val tempRegister = firstNewLocalRegister
        val handledRegister = firstNewLocalRegister + 1

        patchedMethod.addInstructionsWithLabels(
            0,
            """
                iget-object v$tempRegister, p0, Lqup;->c:Lbwyn;
                if-eqz v$tempRegister, :pin_playlist_native_click

                invoke-static {v$tempRegister}, Larbe;->d(Lbwyn;)Lbtcx;
                move-result-object v$tempRegister
                if-eqz v$tempRegister, :pin_playlist_native_click

                iget v$tempRegister, v$tempRegister, Lbtcx;->c:I
                invoke-static {v$tempRegister}, Lbtcw;->a(I)Lbtcw;
                move-result-object v$tempRegister

                iget-object v$handledRegister, p0, Lqup;->c:Lbwyn;

                invoke-static {p1, v$tempRegister, p0, v$handledRegister}, $EXTENSION_CLASS->handleClick(Landroid/view/View;Ljava/lang/Enum;Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v$handledRegister
                if-eqz v$handledRegister, :pin_playlist_native_click

                # Close the flyout through the stock path without dispatching the
                # original Speed Dial endpoint.
                iget-object v$tempRegister, p0, Lqup;->t:Lqui;
                iget-object v$tempRegister, v$tempRegister, Lqui;->a:Lqup;
                iget-object v$tempRegister, v$tempRegister, Lqup;->b:Lcmxx;
                invoke-interface {v$tempRegister}, Lcmxx;->ht()Ljava/lang/Object;
                move-result-object v$tempRegister
                check-cast v$tempRegister, Lbffz;
                invoke-interface {v$tempRegister}, Lbffz;->i()V
                return-void
            """,
            ExternalLabel(
                "pin_playlist_native_click",
                patchedMethod.implementation!!.instructions.first()
            )
        )

        /*
         * The active Speed Dial renderer is a bwzj toggle row. qup.d() checks
         * sharedToggleMenuItemMutations and can replace that row's model just
         * before setting its title, which would turn our clone back into a
         * second native "Pin to Speed Dial" item. Keep native behavior for
         * stock rows, but force the comparison to see our cloned row as
         * already synchronized.
         */
        val menuItemBindMethod =
            PlaylistMenuItemBindFingerprint.method

        val bindInstructions =
            menuItemBindMethod.implementation!!.instructions

        val nativeToggleStateCallIndex =
            bindInstructions.withIndex().first { indexed ->
                val reference =
                    (indexed.value as? ReferenceInstruction)
                        ?.reference as? MethodReference
                        ?: return@first false

                reference.definingClass == "Lqpg;" &&
                    reference.name == "b" &&
                    reference.returnType == "Z" &&
                    reference.parameterTypes
                        .map { it.toString() } ==
                    listOf("Lbwzj;")
            }.index

        check(
            bindInstructions[
                nativeToggleStateCallIndex + 1
            ].opcode == Opcode.MOVE_RESULT
        ) {
            "Expected move-result after qpg.b(bwzj)"
        }

        val nativeToggleStateRegister =
            (bindInstructions[
                nativeToggleStateCallIndex + 1
            ] as OneRegisterInstruction).registerA

        val modelToggleStateIndex =
            (nativeToggleStateCallIndex + 2 until
                bindInstructions.size).first { index ->
                bindInstructions[index].opcode ==
                    Opcode.IGET_BOOLEAN
            }

        val modelToggleStateRegister =
            (bindInstructions[
                modelToggleStateIndex
            ] as TwoRegisterInstruction).registerA

        val toggleComparisonIndex =
            (modelToggleStateIndex + 1 until
                bindInstructions.size).first { index ->
                bindInstructions[index].opcode == Opcode.IF_EQ
            }

        val toggleComparison =
            bindInstructions[
                toggleComparisonIndex
            ] as TwoRegisterInstruction

        check(
            setOf(
                toggleComparison.registerA,
                toggleComparison.registerB
            ) == setOf(
                nativeToggleStateRegister,
                modelToggleStateRegister
            )
        ) {
            "Expected qpg toggle state to be compared with bwzj model state"
        }

        val p0Register =
            menuItemBindMethod.implementation!!.registerCount - 1

        check(
            p0Register <= 15 &&
                nativeToggleStateRegister <= 15 &&
                modelToggleStateRegister <= 15
        ) {
            "qup toggle guard requires compact registers; update the injection to use a range invoke"
        }

        menuItemBindMethod.addInstructionsWithLabels(
            toggleComparisonIndex,
            """
                invoke-static {p0, v$nativeToggleStateRegister, v$modelToggleStateRegister}, $EXTENSION_CLASS->guardNativeToggleMutation(Ljava/lang/Object;ZZ)Z
                move-result v$nativeToggleStateRegister
            """
        )

        /*
         * True pre-submit hook.
         *
         * Capture every Lhyi returned by bfrh.h(index), then reorder the
         * completed List<Lhzq> immediately before bfrh iterates it and converts
         * it into hzc.b's hvg rows. The adapter therefore receives pinned order
         * on its first render instead of being corrected after binding.
         */
        val renderInfoFactory =
            PlaylistAdapterProxyRenderInfoFingerprint.method

        check((renderInfoFactory.accessFlags and 0x8) == 0) {
            "Expected bfrh.h(int) to be an instance method"
        }

        /*
         * Use range invokes so this remains valid even when the obfuscated
         * method has enough locals to place p0/p1 or the return register above
         * the normal invoke instruction's four-bit register limit.
         */
        renderInfoFactory.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->beginAdapterProxyRenderInfo(Ljava/lang/Object;I)V
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->remapAdapterProxySourcePosition(Ljava/lang/Object;I)I
                move-result p1
            """
        )

        val getItemCallIndex =
            renderInfoFactory.implementation!!.instructions
                .withIndex()
                .first { indexed ->
                    val referenceInstruction =
                        indexed.value as? ReferenceInstruction
                            ?: return@first false
                    val reference =
                        referenceInstruction.reference
                            as? MethodReference
                            ?: return@first false

                    reference.definingClass == "Lbewt;" &&
                        reference.name == "getItem" &&
                        reference.returnType ==
                        "Ljava/lang/Object;" &&
                        reference.parameterTypes
                            .map { it.toString() } ==
                        listOf("I")
                }
                .index

        val getItemResult =
            renderInfoFactory.implementation!!.instructions[
                getItemCallIndex + 1
            ] as OneRegisterInstruction

        check(
            renderInfoFactory.implementation!!.instructions[
                getItemCallIndex + 1
            ].opcode == Opcode.MOVE_RESULT_OBJECT
        ) {
            "Expected move-result-object after bewt.getItem"
        }

        renderInfoFactory.addInstructionsWithLabels(
            getItemCallIndex + 2,
            """
                invoke-static/range {v${getItemResult.registerA} .. v${getItemResult.registerA}}, $EXTENSION_CLASS->captureAdapterProxySourceObject(Ljava/lang/Object;)V
            """
        )

        renderInfoFactory.implementation!!.instructions
            .withIndex()
            .filter { indexed ->
                indexed.value.opcode == Opcode.RETURN_OBJECT
            }
            .map { indexed ->
                indexed.index to
                    (indexed.value as OneRegisterInstruction).registerA
            }
            .asReversed()
            .forEach { (returnIndex, returnRegister) ->
                renderInfoFactory.addInstructionsWithLabels(
                    returnIndex,
                    """
                        invoke-static/range {v$returnRegister .. v$returnRegister}, $EXTENSION_CLASS->captureAdapterProxyRenderInfo(Ljava/lang/Object;)V
                    """
                )
            }

        /*
         * Hook every active bfrh source-list mutation, not only its dormant
         * replace-all method. The static dump shows the Library path builds
         * List<Lhzq> through h(index) and submits it through hzc.B/S before
         * hzc converts those render infos into hvg rows.
         */
        val adapterProxyClass =
            PlaylistAdapterProxyRenderInfoFingerprint.classDef

        adapterProxyClass.methods
            .filter { method ->
                method.implementation != null &&
                    method.name != "<init>" &&
                    method.name != "<clinit>" &&
                    (method.accessFlags and 0x8) == 0
            }
            .forEach { method ->
                val instructions =
                    method.implementation!!.instructions

                val buildsRenderInfos =
                    instructions.any { instruction ->
                        val referenceInstruction =
                            instruction as? ReferenceInstruction
                                ?: return@any false
                        val reference =
                            referenceInstruction.reference
                                as? MethodReference
                                ?: return@any false

                        reference.definingClass == "Lbfrh;" &&
                            reference.name == "h" &&
                            reference.returnType == "Lhzq;" &&
                            reference.parameterTypes
                                .map { it.toString() } ==
                            listOf("I")
                    }

                if (!buildsRenderInfos) {
                    return@forEach
                }

                val mutationCalls =
                    instructions
                        .withIndex()
                        .mapNotNull { indexed ->
                            val referenceInstruction =
                                indexed.value as? ReferenceInstruction
                                    ?: return@mapNotNull null
                            val reference =
                                referenceInstruction.reference
                                    as? MethodReference
                                    ?: return@mapNotNull null

                            if (reference.definingClass != "Lhzc;") {
                                return@mapNotNull null
                            }

                            val parameterTypes =
                                reference.parameterTypes
                                    .map { it.toString() }
                            val listParameterIndex =
                                parameterTypes.indexOf(
                                    "Ljava/util/List;"
                                )

                            if (listParameterIndex < 0) {
                                return@mapNotNull null
                            }

                            val invokeRegisters =
                                when (val instruction = indexed.value) {
                                    is FiveRegisterInstruction -> {
                                        listOf(
                                            instruction.registerC,
                                            instruction.registerD,
                                            instruction.registerE,
                                            instruction.registerF,
                                            instruction.registerG,
                                        ).take(instruction.registerCount)
                                    }

                                    is RegisterRangeInstruction ->
                                        (instruction.startRegister until
                                            instruction.startRegister +
                                            instruction.registerCount)
                                            .toList()

                                    else -> return@mapNotNull null
                                }

                            /*
                             * Word zero is the invoke-virtual receiver. Wide
                             * J/D parameters occupy two words; all other
                             * parameters, including List, occupy one.
                             */
                            var listWordIndex = 1
                            for (parameterIndex in 0 until
                                listParameterIndex) {
                                val type =
                                    parameterTypes[parameterIndex]
                                listWordIndex +=
                                    if (type == "J" || type == "D") {
                                        2
                                    } else {
                                        1
                                    }
                            }

                            if (listWordIndex >=
                                invokeRegisters.size) {
                                return@mapNotNull null
                            }

                            Pair(
                                indexed.index,
                                invokeRegisters[listWordIndex]
                            )
                        }
                        .asReversed()

                mutationCalls.forEach {
                    (callIndex, listRegister) ->

                    method.addInstructionsWithLabels(
                        callIndex,
                        """
                            invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->beginAdapterProxyReplaceAll(Ljava/lang/Object;)V
                            invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS->prepareAdapterProxyRenderInfos(Ljava/lang/Object;)V
                        """
                    )
                }
            }

        /*
         * AdapterProxy rows all share one render-info object. Reorder them
         * virtually by translating hyz's visual adapter position before its
         * view-type, stable-ID and bind paths read the native row.
         */
        val libraryAdapterClass =
            PlaylistLithoAdapterBindFingerprint.classDef

        val libraryViewTypeMethod =
            libraryAdapterClass.methods.single {
                it.name == "b" &&
                    it.returnType == "I" &&
                    it.parameters.map { parameter ->
                        parameter.type
                    } == listOf("I")
            }

        libraryViewTypeMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->beginAdapterViewTypePositionRemap(Ljava/lang/Object;)V
                invoke-static/range {p1 .. p1}, $EXTENSION_CLASS->remapAdapterPosition(I)I
                move-result p1
            """
        )

        val libraryStableIdMethod =
            libraryAdapterClass.methods.single {
                it.name == "iv" &&
                    it.returnType == "J" &&
                    it.parameters.map { parameter ->
                        parameter.type
                    } == listOf("I")
            }

        libraryStableIdMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->beginAdapterStableIdPositionRemap(Ljava/lang/Object;)V
                invoke-static/range {p1 .. p1}, $EXTENSION_CLASS->remapAdapterPosition(I)I
                move-result p1
            """
        )

        /*
         * Fast Library adapter hook. Preserve adapter/holder/position at entry,
         * then inspect the fully populated row at every normal return from hyz.o.
         * p1 is either the original holder or its item View at these returns;
         * the extension indexes the pending bind by both identities.
         */
        val libraryBindMethod = PlaylistLithoAdapterBindFingerprint.method

        libraryBindMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1, p2}, $EXTENSION_CLASS->beginBoundLibraryRow(Ljava/lang/Object;Ljava/lang/Object;I)V
                invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->beginAdapterBindPositionRemap(Ljava/lang/Object;)V
                invoke-static/range {p2 .. p2}, $EXTENSION_CLASS->remapAdapterPosition(I)I
                move-result p2
            """
        )

        val libraryBindReturnIndices =
            libraryBindMethod.implementation!!.instructions
                .withIndex()
                .filter { indexed ->
                    indexed.value.opcode == Opcode.RETURN_VOID
                }
                .map { indexed -> indexed.index }
                .asReversed()

        libraryBindReturnIndices.forEach { returnIndex ->
            libraryBindMethod.addInstructionsWithLabels(
                returnIndex,
                """
                    invoke-static {p1}, $EXTENSION_CLASS->finishBoundLibraryRow(Ljava/lang/Object;)V
                """
            )
        }

        /*
         * Lightweight row-key bridge. qot.k still receives the clicked overflow
         * View and the byhm source object. Resolve qot.k directly from qot.j's
         * matched class so no separate global fingerprint is required.
         */
        val flyoutViewMethod = PlaylistFlyoutSourceFingerprint.classDef.methods.single {
            it.name == "k" &&
                it.returnType == "V" &&
                it.parameters.map { parameter -> parameter.type } == listOf(
                    "Lbwyr;",
                    "Landroid/view/View;",
                    "Ljava/lang/Object;",
                    "Laryl;",
                )
        }

        flyoutViewMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p2, p3}, $EXTENSION_CLASS->captureFlyoutViewContext(Landroid/view/View;Ljava/lang/Object;)V
            """
        )

        /*
         * Capture canonical identity from the reusable native menu, then pass
         * a detached copy downstream when the optional row is injected. This
         * keeps qot.j's original bwyr protobuf safe for later reopenings.
         */
        PlaylistFlyoutSourceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1, p2, p3}, $EXTENSION_CLASS->captureFlyoutSource(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
                invoke-static {p1, p2}, $EXTENSION_CLASS->prepareFlyoutMenu(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, Lbwyr;
            """
        )

    }
}
