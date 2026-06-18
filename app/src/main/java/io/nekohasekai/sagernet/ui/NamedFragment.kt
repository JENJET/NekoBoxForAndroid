package io.nekohasekai.sagernet.ui

import androidx.fragment.app.Fragment

abstract class NamedFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    fun name() = name0()
    protected abstract fun name0(): String

}