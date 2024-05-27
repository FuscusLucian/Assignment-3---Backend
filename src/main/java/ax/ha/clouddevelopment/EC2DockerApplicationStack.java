package ax.ha.clouddevelopment;

import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneAttributes;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.constructs.Construct;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;


import java.util.List;

public class EC2DockerApplicationStack extends Stack {

    // Do not remove these variables. The hosted zone can be used later when creating DNS records
    private final IHostedZone hostedZone = HostedZone.fromHostedZoneAttributes(this, "HaHostedZone", HostedZoneAttributes.builder()
            .hostedZoneId("Z0413857YT73A0A8FRFF")
            .zoneName("cloud-ha.com")
            .build());

    // Do not remove, you can use this when defining what VPC your security group, instance and load balancer should be part of.
    private final IVpc vpc = Vpc.fromLookup(this, "MyVpc", VpcLookupOptions.builder()
            .isDefault(true)
            .build());

    public EC2DockerApplicationStack(final Construct scope, final String id, final StackProps props, final String groupName) {
        super(scope, id, props);

        // Creates a SecurityGroup within the specified VPC.
        // The SecurityGroup will allow all outbound traffic.
        SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        // Allows HTTP traffic on port 80.
        securityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Allow HTTP traffic");

        // Creates an IAM Role that allows EC2 instances to assume the role.
        // The role is assumed by the "ec2.amazonaws.com" service principal.
        // The role has two managed policies attached:
        // 1. AmazonSSMManagedInstanceCore: This policy allows the instance to use Systems Manager for managing instances.
        // 2. AmazonEC2ContainerRegistryReadOnly: This policy allows the instance to read images from the Amazon ECR registry.
        Role role = Role.Builder.create(this, "Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")
                ))
                .build();

        // Makes an EC2 Instance with the specified instance type, machine image, and VPC.
        // The instance will use the previously created security group and IAM role.
        // The instance will be placed in a public subnet within the VPC.
        Instance instance = Instance.Builder.create(this, "Instance")
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())
                .vpc(vpc)
                .role(role)
                .securityGroup(securityGroup)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .build();

        // Adds user data to the instance that installs Docker, starts the Docker service,
        // logs into the Amazon ECR registry, and runs a Docker container.
        instance.addUserData(
                "yum install docker -y",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "docker run -d --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
        );

        // Create Load Balancer
        ApplicationLoadBalancer lb = ApplicationLoadBalancer.Builder.create(this, "LB")
                .vpc(vpc)
                .internetFacing(true) // makes it accessible via internet
                .build();

        // Listener for the balancer at port 80 to allow incoming traffic
        ApplicationListener listener = lb.addListener("Listener", BaseApplicationListenerProps.builder()
                .port(80)
                .open(true)
                .build());

        // Adds targets to the listener. The targets are the EC2 instances that will receive traffic from the load balancer.
        // In this case, only one instance is added as a target.
        listener.addTargets("Target", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(List.of(new InstanceTarget(instance)))
                .build());

        // Retrieve the Load Balancer's Security Group, Group ID and add ingress rule
        // The getLoadBalancerSecurityGroups() method returns a list of security group IDs associated with the load balancer.
        // Since this is a list and we need a single security group ID, we use the Fn.select() function to select the first element from the list (index 0).
        String lbSecurityGroupId = Fn.select(0, lb.getLoadBalancerSecurityGroups());

        // Adds an ingress rule to the EC2 instance's security group to allow traffic from the Load Balancer's security group
        // Peer.securityGroupId(lbSecurityGroupId) creates a new IPeer object that represents the security group of the load balancer.
        // This essentially allows the security group of the load balancer to send traffic to the EC2 instance on port 80 (HTTP).
        // Port.tcp(80) specifies that the rule is for TCP traffic on port 80.
        securityGroup.addIngressRule(Peer.securityGroupId(lbSecurityGroupId), Port.tcp(80), "Allow traffic from load balancer");

        // Creates a new ARecord in Route53 that maps a friendly domain name to the load balancer's DNS name.
        ARecord aRecord = ARecord.Builder.create(this, "AliasRecord")
                .zone(hostedZone) // The hosted zone to which the ARecord will be added
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(lb))) // Points the ARecord to the load balancer
                .recordName(groupName + "-api.cloud-ha.com") // Sets the record name (example, svelic-api.cloud-ha.com)
                .build();


    }
}

