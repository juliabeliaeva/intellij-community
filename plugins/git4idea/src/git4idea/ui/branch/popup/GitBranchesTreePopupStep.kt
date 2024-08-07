// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ui.branch.popup

import com.intellij.dvcs.DvcsUtil
import com.intellij.dvcs.getCommonName
import com.intellij.dvcs.ui.DvcsBundle
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
import com.intellij.openapi.ui.popup.SpeedSearchFilter
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.treeStructure.Tree
import git4idea.GitBranch
import git4idea.GitReference
import git4idea.GitTag
import git4idea.GitVcs
import git4idea.actions.branch.GitBranchActionsUtil
import git4idea.actions.branch.GitBranchActionsUtil.userWantsSyncControl
import git4idea.repo.GitRefUtil
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitBranchPopupActions.EXPERIMENTAL_BRANCH_POPUP_ACTION_GROUP
import git4idea.ui.branch.GitBranchesMatcherWrapper
import git4idea.ui.branch.tree.*
import javax.swing.JComponent
import javax.swing.tree.TreePath

class GitBranchesTreePopupStep(internal val project: Project,
                               internal val selectedRepository: GitRepository?,
                               internal val repositories: List<GitRepository>,
                               private val isFirstStep: Boolean) : PopupStep<Any> {

  internal val affectedRepositories get() = selectedRepository?.let(::listOf) ?: repositories

  private val presentationFactory = PresentationFactory()
  private var finalRunnable: Runnable? = null

  override fun getFinalRunnable() = finalRunnable

  internal var treeModel: GitBranchesTreeModel
    private set

  private val topLevelItems = mutableListOf<Any>()

  init {
    if (ExperimentalUI.isNewUI() && isFirstStep) {
      val experimentalUIActionsGroup = ActionManager.getInstance().getAction(EXPERIMENTAL_BRANCH_POPUP_ACTION_GROUP) as? ActionGroup
      if (experimentalUIActionsGroup != null) {
        topLevelItems.addAll(createTopLevelActionItems(project, experimentalUIActionsGroup, presentationFactory, selectedRepository, affectedRepositories).addSeparators())
        if (topLevelItems.isNotEmpty()) {
          topLevelItems.add(GitBranchesTreePopup.createTreeSeparator())
        }
      }
    }
    val actionGroup = ActionManager.getInstance().getAction(TOP_LEVEL_ACTION_GROUP) as? ActionGroup
    if (actionGroup != null) {
      // get selected repo inside actions
      topLevelItems.addAll(createTopLevelActionItems(project, actionGroup, presentationFactory, selectedRepository, affectedRepositories).addSeparators())
      if (topLevelItems.isNotEmpty()) {
        topLevelItems.add(GitBranchesTreePopup.createTreeSeparator())
      }
    }

    treeModel = createTreeModel(false)
  }
  private fun createTreeModel(filterActive: Boolean): GitBranchesTreeModel {
    return when {
      !filterActive && repositories.size > 1
      && !userWantsSyncControl(project) && selectedRepository != null -> {
        GitBranchesTreeSelectedRepoModel(project, selectedRepository, repositories, topLevelItems)
          .apply(GitBranchesTreeSelectedRepoModel::init)
      }
      filterActive && repositories.size > 1 -> {
        GitBranchesTreeMultiRepoFilteringModel(project, repositories, topLevelItems).apply(GitBranchesTreeMultiRepoFilteringModel::init)
      }
      !filterActive && repositories.size > 1 -> GitBranchesTreeMultiRepoModel(project, repositories, topLevelItems)
      else -> GitBranchesTreeSingleRepoModel(project, repositories.first(), topLevelItems).apply(GitBranchesTreeSingleRepoModel::init)
    }
  }

  private fun List<PopupFactoryImpl.ActionItem>.addSeparators(): List<Any> {
    val actionsWithSeparators = mutableListOf<Any>()
    for (action in this) {
      if (action.isPrependWithSeparator) {
        actionsWithSeparators.add(GitBranchesTreePopup.createTreeSeparator(action.separatorText))
      }
      actionsWithSeparators.add(action)
    }
    return actionsWithSeparators
  }

  fun isBranchesDiverged(): Boolean {
    return repositories.size > 1
           && getCommonName(repositories) { GitRefUtil.getCurrentReference(it)?.fullName ?: return@getCommonName null } == null
           && userWantsSyncControl(project)
  }

  fun getPreferredSelection(): TreePath? {
    return treeModel.getPreferredSelection()
  }

  fun createTreePathFor(value: Any): TreePath? {
    return createTreePathFor(treeModel, value)
  }

  internal fun setPrefixGrouping(state: Boolean) {
    treeModel.isPrefixGrouping = state
  }

  fun setSearchPattern(pattern: String?) {
    if (pattern == null || pattern == "/") {
      treeModel.filterBranches()
      return
    }

    val trimmedPattern = pattern.trim() //otherwise Character.isSpaceChar would affect filtering
    val matcher = GitBranchesMatcherWrapper(NameUtil.buildMatcher("*$trimmedPattern").build())
    treeModel.filterBranches(matcher)
  }

  fun updateTreeModelIfNeeded(tree: Tree, pattern: String?) {
    if (!isFirstStep || affectedRepositories.size == 1) {
      require(tree.model != null) { "Provided tree with null model" }
      return
    }

    val filterActive = !(pattern.isNullOrBlank() || pattern == "/")
    treeModel = createTreeModel(filterActive)
    tree.model = treeModel
  }

  override fun hasSubstep(selectedValue: Any?): Boolean {
    val userValue = selectedValue ?: return false

    return if (userValue is PopupFactoryImpl.ActionItem) {
      userValue.isEnabled && userValue.action is ActionGroup
    }
    else {
      treeModel.isSelectable(selectedValue)
    }
  }

  fun isSelectable(node: Any?): Boolean {
    return treeModel.isSelectable(node)
  }

  override fun onChosen(selectedValue: Any?, finalChoice: Boolean): PopupStep<out Any>? {
    if (selectedValue is GitBranchesTreeModel.TopLevelRepository) {
      return GitBranchesTreePopupStep(project, selectedValue.repository, listOf(selectedValue.repository), false)
    }

    if (selectedValue is GitRepository) {
      return GitBranchesTreePopupStep(project, selectedValue, listOf(selectedValue), false)
    }

    val refUnderRepository = selectedValue as? GitBranchesTreeModel.RefUnderRepository
    val reference = selectedValue as? GitReference ?: refUnderRepository?.ref

    if (reference != null) {
      val actionGroup = ActionManager.getInstance().getAction(BRANCH_ACTION_GROUP) as? ActionGroup ?: DefaultActionGroup()
      return createActionStep(actionGroup, project, selectedRepository,
                              refUnderRepository?.repository?.let(::listOf) ?: affectedRepositories, reference)
    }

    if (selectedValue is PopupFactoryImpl.ActionItem) {
      if (!selectedValue.isEnabled) return FINAL_CHOICE
      val action = selectedValue.action
      if (action is ActionGroup && (!finalChoice || !selectedValue.isPerformGroup)) {
        return createActionStep(action, project, selectedRepository, affectedRepositories)
      }
      else {
        finalRunnable = Runnable {
          val place = if (isFirstStep) TOP_LEVEL_ACTION_PLACE else SINGLE_REPOSITORY_ACTION_PLACE
          val dataContext = createDataContext(project, null, selectedRepository, affectedRepositories)
          ActionUtil.invokeAction(action, dataContext, place, null, null)
        }
      }
    }

    return FINAL_CHOICE
  }

  override fun getTitle(): String? =
    when {
      ExperimentalUI.isNewUI() -> null
      !isFirstStep -> null
      repositories.size > 1 -> DvcsBundle.message("branch.popup.vcs.name.branches", GitVcs.DISPLAY_NAME.get())
      else -> repositories.single().let {
        DvcsBundle.message("branch.popup.vcs.name.branches.in.repo", it.vcs.displayName, DvcsUtil.getShortRepositoryName(it))
      }
    }

  override fun canceled() {}

  override fun isMnemonicsNavigationEnabled() = false

  override fun getMnemonicNavigationFilter() = null

  override fun isSpeedSearchEnabled() = true

  override fun getSpeedSearchFilter() = SpeedSearchFilter<Any> { node ->
    when (node) {
      is GitBranch -> node.name
      else -> node?.let { GitBranchesTreeRenderer.getText(node, treeModel, repositories) } ?: ""
    }
  }

  override fun isAutoSelectionEnabled() = false

  companion object {
    internal const val HEADER_SETTINGS_ACTION_GROUP = "Git.Branches.Popup.Settings"
    private const val TOP_LEVEL_ACTION_GROUP = "Git.Branches.List"
    internal const val SPEED_SEARCH_DEFAULT_ACTIONS_GROUP = "Git.Branches.Popup.SpeedSearch"
    private const val BRANCH_ACTION_GROUP = "Git.Branch"

    internal val SINGLE_REPOSITORY_ACTION_PLACE = ActionPlaces.getPopupPlace("GitBranchesPopup.SingleRepo.Branch.Actions")
    internal val TOP_LEVEL_ACTION_PLACE = ActionPlaces.getPopupPlace("GitBranchesPopup.TopLevel.Branch.Actions")

    private fun createTopLevelActionItems(project: Project,
                                          actionGroup: ActionGroup,
                                          presentationFactory: PresentationFactory,
                                          selectedRepository: GitRepository?,
                                          repositories: List<GitRepository>): List<PopupFactoryImpl.ActionItem> {
      val dataContext = createDataContext(project, null, selectedRepository, repositories)
      val actionItems = ActionPopupStep.createActionItems(
        actionGroup, dataContext, TOP_LEVEL_ACTION_PLACE, presentationFactory,
        ActionPopupOptions.showDisabled())

      if (actionItems.singleOrNull()?.action == Utils.EMPTY_MENU_FILLER) {
        return emptyList()
      }

      return actionItems
    }

    private fun createActionStep(actionGroup: ActionGroup,
                                 project: Project,
                                 selectedRepository: GitRepository?,
                                 repositories: List<GitRepository>,
                                 reference: GitReference? = null): ListPopupStep<*> {
      val dataContext = createDataContext(project, null, selectedRepository, repositories, reference)
      return JBPopupFactory.getInstance()
        .createActionsStep(actionGroup, dataContext, SINGLE_REPOSITORY_ACTION_PLACE, false, true, null, null, false, 0, false)
    }

    internal fun createDataContext(project: Project,
                                   component: JComponent?,
                                   selectedRepository: GitRepository?,
                                   repositories: List<GitRepository>,
                                   reference: GitReference? = null): DataContext =
      CustomizedDataContext.withSnapshot(
        DataManager.getInstance().getDataContext(component)) { sink ->
        sink[CommonDataKeys.PROJECT] = project
        sink[GitBranchActionsUtil.REPOSITORIES_KEY] = repositories
        sink[GitBranchActionsUtil.SELECTED_REPO_KEY] = selectedRepository
        if (reference is GitBranch) {
          sink[GitBranchActionsUtil.BRANCHES_KEY] = listOf(reference)
        }
        else if (reference is GitTag) {
          sink[GitBranchActionsUtil.TAGS_KEY] = listOf(reference)
        }
        sink[GitBranchActionsUtil.BRANCHES_KEY] = (reference as? GitBranch)?.let(::listOf)
      }

  }
}
