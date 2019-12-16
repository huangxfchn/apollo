package com.ctrip.framework.apollo.adminservice.controller;

import com.ctrip.framework.apollo.biz.entity.GrayReleaseRule;
import com.ctrip.framework.apollo.biz.entity.Namespace;
import com.ctrip.framework.apollo.biz.message.MessageSender;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.service.NamespaceBranchService;
import com.ctrip.framework.apollo.biz.service.NamespaceService;
import com.ctrip.framework.apollo.biz.utils.ReleaseMessageKeyGenerator;
import com.ctrip.framework.apollo.common.constants.NamespaceBranchStatus;
import com.ctrip.framework.apollo.common.dto.GrayReleaseRuleDTO;
import com.ctrip.framework.apollo.common.dto.NamespaceDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.common.utils.GrayReleaseRuleItemTransformer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NamespaceBranchController {

  private final MessageSender messageSender;
  private final NamespaceBranchService namespaceBranchService;
  private final NamespaceService namespaceService;

  public NamespaceBranchController(
      final MessageSender messageSender,
      final NamespaceBranchService namespaceBranchService,
      final NamespaceService namespaceService) {
    this.messageSender = messageSender;
    this.namespaceBranchService = namespaceBranchService;
    this.namespaceService = namespaceService;
  }

  /**
   * 创建灰度分支
   * @param appId
   * @param clusterName
   * @param namespaceName
   * @param operator
   * @return
   */
  @PostMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches")
  public NamespaceDTO createBranch(@PathVariable String appId,
                                   @PathVariable String clusterName,
                                   @PathVariable String namespaceName,
                                   @RequestParam("operator") String operator) {
    /***
     * 检查当前namespace是否存在
     */
    checkNamespace(appId, clusterName, namespaceName);
    /***
     * 根据当前namespace创建一个分支
     */
    Namespace createdBranch = namespaceBranchService.createBranch(appId, clusterName, namespaceName, operator);

    return BeanUtils.transform(NamespaceDTO.class, createdBranch);
  }

  @GetMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/rules")
  public GrayReleaseRuleDTO findBranchGrayRules(@PathVariable String appId,
                                                @PathVariable String clusterName,
                                                @PathVariable String namespaceName,
                                                @PathVariable String branchName) {

    checkBranch(appId, clusterName, namespaceName, branchName);

    GrayReleaseRule rules = namespaceBranchService.findBranchGrayRules(appId, clusterName, namespaceName, branchName);
    if (rules == null) {
      return null;
    }
    GrayReleaseRuleDTO ruleDTO =
        new GrayReleaseRuleDTO(rules.getAppId(), rules.getClusterName(), rules.getNamespaceName(),
                               rules.getBranchName());

    ruleDTO.setReleaseId(rules.getReleaseId());

    ruleDTO.setRuleItems(GrayReleaseRuleItemTransformer.batchTransformFromJSON(rules.getRules()));

    return ruleDTO;
  }

  /***
   *
   * @param appId 应用id 10001
   * @param clusterName 集群名称 default
   * @param namespaceName namespace名称 seata-properties
   * @param branchName 分支名称 20191216205727-de14b4e2b10903b9
   * @param newRuleDto GrayReleaseRuleItemDTO{clientAppId=10001, clientIpList=[127.0.0.1, 127.0.0.11]}
   * 1、校验父cluster和子 cluster是否存在
   * 2、更新子 Namespace 的灰度发布规则，会新增一条新的GrayReleaseRule，删除旧的GrayReleaseRule
   * 3、向队列apollo-release发送一条消息， GrayReleaseRulesHolder会处理
   */
  @Transactional
  @PutMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/rules")
  public void updateBranchGrayRules(@PathVariable String appId, @PathVariable String clusterName,
                                    @PathVariable String namespaceName, @PathVariable String branchName,
                                    @RequestBody GrayReleaseRuleDTO newRuleDto) {
    // 校验父cluster和子 cluster是否存在
    checkBranch(appId, clusterName, namespaceName, branchName);
    // 将 GrayReleaseRuleDTO 转成 GrayReleaseRule 对象
    GrayReleaseRule newRules = BeanUtils.transform(GrayReleaseRule.class, newRuleDto);
    // JSON 化规则为字符串，并设置到 GrayReleaseRule 对象中
    newRules.setRules(GrayReleaseRuleItemTransformer.batchTransformToJSON(newRuleDto.getRuleItems()));
    // 设置 GrayReleaseRule 对象的 `branchStatus` 为 ACTIVE
    newRules.setBranchStatus(NamespaceBranchStatus.ACTIVE);
    // 更新子 Namespace 的灰度发布规则
    namespaceBranchService.updateBranchGrayRules(appId, clusterName, namespaceName, branchName, newRules);
    // 发送 Release 消息，交给GrayReleaseRulesHolder处理
    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, clusterName, namespaceName),
                              Topics.APOLLO_RELEASE_TOPIC);
  }

  @Transactional
  @DeleteMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}")
  public void deleteBranch(@PathVariable String appId, @PathVariable String clusterName,
                           @PathVariable String namespaceName, @PathVariable String branchName,
                           @RequestParam("operator") String operator) {

    checkBranch(appId, clusterName, namespaceName, branchName);

    namespaceBranchService
        .deleteBranch(appId, clusterName, namespaceName, branchName, NamespaceBranchStatus.DELETED, operator);

    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, clusterName, namespaceName),
                              Topics.APOLLO_RELEASE_TOPIC);

  }

  @GetMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches")
  public NamespaceDTO loadNamespaceBranch(@PathVariable String appId, @PathVariable String clusterName,
                                          @PathVariable String namespaceName) {

    checkNamespace(appId, clusterName, namespaceName);

    Namespace childNamespace = namespaceBranchService.findBranch(appId, clusterName, namespaceName);
    if (childNamespace == null) {
      return null;
    }

    return BeanUtils.transform(NamespaceDTO.class, childNamespace);
  }

  /***
   * 检查clusterName、namespace存在
   * 检查灰度分支branchName、namespaceName是否存在存在
   * @param appId 应用id10001
   * @param clusterName 父cluster名称default
   * @param namespaceName  namespace名称 seata-properties
   * @param branchName 子cluster名称 20191216205727-de14b4e2b10903b9
   */
  private void checkBranch(String appId, String clusterName, String namespaceName, String branchName) {
    //1. check parent namespace
    checkNamespace(appId, clusterName, namespaceName);

    //2. check child namespace
    Namespace childNamespace = namespaceService.findOne(appId, branchName, namespaceName);
    if (childNamespace == null) {
      throw new BadRequestException(String.format("Namespace's branch not exist. AppId = %s, ClusterName = %s, "
                                                  + "NamespaceName = %s, BranchName = %s",
                                                  appId, clusterName, namespaceName, branchName));
    }

  }

  /***
   * 检查clusterName、namespace是否已存在
   * @param appId 应用id
   * @param clusterName 集群名称
   * @param namespaceName namespace
   */
  private void checkNamespace(String appId, String clusterName, String namespaceName) {
    //查询父namespace
    Namespace parentNamespace = namespaceService.findOne(appId, clusterName, namespaceName);
    // 若父 Namespace 不存在，抛出 BadRequestException 异常
    if (parentNamespace == null) {
      throw new BadRequestException(String.format("Namespace not exist. AppId = %s, ClusterName = %s, NamespaceName = %s", appId,
                                                  clusterName, namespaceName));
    }
  }


}
